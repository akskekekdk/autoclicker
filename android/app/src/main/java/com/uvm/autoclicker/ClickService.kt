package com.uvm.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.random.Random

/**
 * 접근성 서비스: 플로팅 컨트롤 패널과 번호가 붙은 위치 마커를 화면 위에 띄우고,
 * 실행 시 마커 위치를 순서대로 터치(dispatchGesture)합니다.
 */
class ClickService : AccessibilityService() {

    companion object {
        var instance: ClickService? = null
            private set

        private const val MARKER_DP = 48
        /** 터치 감지층에서 터치 위치마다 비워 두는 구멍 크기 */
        private const val HOLE_DP = 24
        /** 한 제스처로 묶어 보내는 터치 수와 묶음 길이 상한 (정지 반응 속도와의 균형) */
        private const val MAX_BATCH_STROKES = 20
        private const val BATCH_WINDOW_MS = 300L
        /**
         * 정지 후 이 시간 동안은 마커가 터치를 받지 않는다. 이미 보낸 묶음의 남은 터치가
         * 마커에 닿아 설정 창이 열리는 것을 막는다 (묶음 길이 + 여유).
         */
        private const val STOP_GRACE_MS = 800L
        /** 실행 시간 제한 확인/남은 시간 표시 갱신 주기 */
        private const val TIME_CHECK_MS = 30_000L
    }

    private class Marker(val view: TextView, val params: WindowManager.LayoutParams)

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var config = Config()
    private val markers = mutableListOf<Marker>()

    private var panel: View? = null
    private lateinit var expandedView: LinearLayout
    private lateinit var foldBtn: TextView
    private lateinit var orientBtn: TextView
    private lateinit var handleView: TextView
    private var foldable: List<View> = emptyList()
    private var minimized = false
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var playBtn: TextView
    private lateinit var infoText: TextView

    private var running = false

    // 화면 터치 감지층 조각들 (addTouchCatchers 참고)
    private val catchers = mutableListOf<View>()
    /** 실행 중 각 포인트의 화면 좌표 */
    private var targets: List<FloatArray> = emptyList()
    private var stoppedByTouchAt = 0L
    private var stoppedAt = 0L
    /** 실행 시간 제한이 있을 때 정지할 시각 (uptime 기준), 없으면 0 */
    private var runEndAt = 0L
    private var index = 0
    /** 현재 포인트를 이번 바퀴에서 몇 번 터치했는지 */
    private var tapRepeat = 0
    private var cycle = 0

    private val markerPx by lazy { dp(MARKER_DP) }

    // ------------------------------------------------------------ lifecycle
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        config = Store.load(this)
        showPanel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() = stop()

    override fun onDestroy() {
        if (::wm.isInitialized) hidePanel()
        instance = null
        super.onDestroy()
    }

    // --------------------------------------------------------------- public
    val isPanelShown get() = panel != null

    fun showPanel() {
        if (panel != null) return
        buildPanel()
        rebuildMarkers()
    }

    fun hidePanel() {
        stop()
        removeMarkers()
        panel?.let { wm.removeView(it) }
        panel = null
    }

    /** SettingsActivity에서 설정이 바뀌었을 때 호출. */
    fun reload() {
        stop()
        config = Store.load(this)
        if (panel != null) rebuildMarkers()
    }

    // ------------------------------------------------------------- overlays
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun overlayParams(x: Int, y: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.x = x
        this.y = y
    }

    /** touchView를 끌면 window(오버레이 창)가 이동, 짧게 누르면 onTap. */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(
        touchView: View,
        window: View,
        params: WindowManager.LayoutParams,
        onTap: () -> Unit = {},
        onDragEnd: () -> Unit = {},
    ) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        touchView.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        wm.updateViewLayout(window, params)
                    }
                }
                MotionEvent.ACTION_UP -> if (dragging) onDragEnd() else onTap()
            }
            true
        }
    }

    /** 아이콘 아래에 작은 글씨로 기능 이름을 붙인 패널 버튼. */
    private fun panelButton(icon: String, label: String, onClick: () -> Unit) = TextView(this).apply {
        text = buttonText(icon, label)
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setLineSpacing(0f, 0.9f)
        layoutParams = LinearLayout.LayoutParams(dp(52), dp(50))
        setOnClickListener { onClick() }
    }

    private fun buttonText(icon: String, label: String) = SpannableString("$icon\n$label").apply {
        setSpan(RelativeSizeSpan(0.5f), icon.length + 1, length, 0)
    }

    private fun buildPanel() {
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#DD222222"))
            cornerRadius = dp(12).toFloat()
        }
        expandedView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = bg
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }

        val handle = TextView(this).apply {
            text = "⠿"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
        }
        handleView = handle
        playBtn = panelButton("▶", "시작") { toggle() }
        infoText = TextView(this).apply {
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
        }
        orientBtn = panelButton("⇄", "가로") { togglePanelOrientation() }

        foldBtn = panelButton("▲", "접기") { setMinimized(!minimized) }
        // 접었을 때 숨길 버튼들 (핸들과 접기/펼치기 버튼은 항상 보임)
        foldable = listOf(
            playBtn,
            panelButton("＋", "추가") { addPoint() },
            panelButton("－", "삭제") { removeLastPoint() },
            panelButton("💾", "매크로") { showMacroDialog() },
            panelButton("⚙", "설정") { openSettings() },
            orientBtn,
            panelButton("✕", "닫기") { hidePanel() },
            infoText,
        )

        expandedView.addView(handle)
        foldable.dropLast(1).forEach { expandedView.addView(it) }
        expandedView.addView(foldBtn)
        expandedView.addView(infoText)

        panelParams = overlayParams(0, resources.displayMetrics.heightPixels / 4)
        makeDraggable(handle, expandedView, panelParams)
        applyPanelOrientation()
        wm.addView(expandedView, panelParams)
        panel = expandedView
        setMinimized(false)
        updateInfo()
    }

    private fun togglePanelOrientation() {
        config.panelHorizontal = !config.panelHorizontal
        Store.save(this, config)
        applyPanelOrientation()
        // 방향을 바꾼 뒤 패널이 화면 밖으로 나가지 않도록 위치를 보정
        expandedView.post {
            val dm = resources.displayMetrics
            panelParams.x = panelParams.x.coerceIn(0, (dm.widthPixels - expandedView.width).coerceAtLeast(0))
            panelParams.y = panelParams.y.coerceIn(0, (dm.heightPixels - expandedView.height).coerceAtLeast(0))
            if (panel != null) wm.updateViewLayout(expandedView, panelParams)
        }
    }

    /** 패널을 세로(기본) 또는 가로로 배치한다. 가로일 때는 휴대폰 폭에 맞도록 버튼을 조금 좁힌다. */
    private fun applyPanelOrientation() {
        val horizontal = config.panelHorizontal
        val btnW = dp(if (horizontal) 42 else 52)
        val btnH = dp(50)
        expandedView.orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        expandedView.gravity = if (horizontal) Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL
        if (horizontal) {
            expandedView.setPadding(dp(4), dp(2), dp(4), dp(2))
        } else {
            expandedView.setPadding(dp(2), dp(4), dp(2), dp(4))
        }
        for (i in 0 until expandedView.childCount) {
            val child = expandedView.getChildAt(i)
            child.layoutParams = when (child) {
                handleView -> if (horizontal) LinearLayout.LayoutParams(dp(24), btnH)
                else LinearLayout.LayoutParams(btnW, dp(28))
                infoText -> if (horizontal) LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, btnH)
                else LinearLayout.LayoutParams(btnW, LinearLayout.LayoutParams.WRAP_CONTENT)
                else -> LinearLayout.LayoutParams(btnW, btnH)
            }
        }
        infoText.minWidth = if (horizontal) dp(36) else 0
        orientBtn.text = if (horizontal) buttonText("⇅", "세로") else buttonText("⇄", "가로")
    }

    /** 패널을 접거나 펼친다. 같은 버튼을 다시 누르면 반대로 동작. 실행 중이 아니면 마커도 함께 숨긴다. */
    private fun setMinimized(value: Boolean) {
        minimized = value
        foldable.forEach { it.visibility = if (value) View.GONE else View.VISIBLE }
        foldBtn.text = if (value) buttonText("▼", "펼치기") else buttonText("▲", "접기")
        applyMarkerVisibility()
    }

    private fun applyMarkerVisibility() {
        val visible = !minimized || running
        markers.forEach { it.view.visibility = if (visible) View.VISIBLE else View.GONE }
    }

    private fun markerBackground(active: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (active) Color.parseColor("#AA00C853") else Color.parseColor("#88E53935"))
        setStroke(dp(2), Color.WHITE)
    }

    /** 마커 글자: 큰 번호 + 작은 대기시간(ms), 여러 번 터치하면 "×횟수". */
    private fun markerText(number: Int, point: ClickPoint): SpannableString {
        val base = if (point.action == Action.TAP) "${point.delayMs}" else "${point.action.arrow}${point.delayMs}"
        val withTaps = if (point.taps > 1) "$base×${point.taps}" else base
        val detail = if (point.chance < 100) "$withTaps\n${point.chance}%" else withTaps
        return SpannableString("$number\n$detail").apply {
            setSpan(RelativeSizeSpan(0.55f), number.toString().length + 1, length, 0)
        }
    }

    private fun createMarker(number: Int, point: ClickPoint): Marker {
        val view = TextView(this).apply {
            text = markerText(number, point)
            textSize = 15f
            setLineSpacing(0f, 0.85f)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = markerBackground(false)
            layoutParams = android.view.ViewGroup.LayoutParams(markerPx, markerPx)
        }
        val params = overlayParams(point.x - markerPx / 2, point.y - markerPx / 2).apply {
            width = markerPx
            height = markerPx
        }
        val marker = Marker(view, params)
        makeDraggable(
            view, view, params,
            onTap = { editPoint(number - 1) },
            onDragEnd = { savePositions() },
        )
        wm.addView(view, params)
        return marker
    }

    /** 마커를 탭하면 그 포인트의 대기 시간과 터치 횟수를 바로 입력하는 창을 띄운다. */
    private fun editPoint(index: Int) {
        if (running || index !in config.points.indices) return
        // 정지 직후 남은 자동 터치가 마커에 닿은 경우는 무시
        if (SystemClock.uptimeMillis() - stoppedAt < STOP_GRACE_MS) return
        val point = config.points[index]
        val theme = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        fun numberInput(value: Number) = EditText(theme).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value.toString())
            setSelectAllOnFocus(true)
        }
        fun label(s: String) = TextView(theme).apply {
            text = s
            setPadding(0, dp(8), 0, 0)
        }
        val delayInput = numberInput(point.delayMs)
        val tapsInput = numberInput(point.taps)
        val swipeDistInput = numberInput(point.swipeDp)
        val swipeMsInput = numberInput(point.swipeMs)
        val chanceInput = numberInput(point.chance)
        val swipeBox = LinearLayout(theme).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("밀기 거리 (dp, 화면 높이는 보통 700~900)"))
            addView(swipeDistInput)
            addView(label("밀기 시간 (ms, 길수록 천천히·정확히 밀림)"))
            addView(swipeMsInput)
        }
        val actionGroup = RadioGroup(theme)
        Action.entries.forEachIndexed { i, a ->
            actionGroup.addView(RadioButton(theme).apply {
                id = View.generateViewId()
                text = a.label
                tag = a
                isChecked = a == point.action
            })
        }
        fun selectedAction(): Action =
            actionGroup.findViewById<RadioButton>(actionGroup.checkedRadioButtonId)?.tag as? Action ?: Action.TAP
        swipeBox.visibility = if (point.action == Action.TAP) View.GONE else View.VISIBLE
        actionGroup.setOnCheckedChangeListener { _, _ ->
            swipeBox.visibility = if (selectedAction() == Action.TAP) View.GONE else View.VISIBLE
        }
        val box = LinearLayout(theme).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(label("동작"))
            addView(actionGroup)
            addView(swipeBox)
            addView(label("동작 후 대기 시간 (ms)"))
            addView(delayInput)
            addView(label("연속 반복 횟수"))
            addView(tapsInput)
            addView(label("실행 확률 (%, 100 = 항상)"))
            addView(chanceInput)
        }
        val scroll = ScrollView(theme).apply { addView(box) }
        val apply = { delay: Long, taps: Int ->
            point.delayMs = delay
            point.taps = taps
            Store.save(this, config)
            markers.getOrNull(index)?.view?.text = markerText(index + 1, point)
        }
        val dialog = AlertDialog.Builder(theme)
            .setTitle("${index + 1}번 포인트")
            .setView(scroll)
            .setPositiveButton("저장") { _, _ ->
                val delay = delayInput.text.toString().toLongOrNull()
                val taps = tapsInput.text.toString().toIntOrNull()
                val dist = swipeDistInput.text.toString().toIntOrNull()
                val swipeMs = swipeMsInput.text.toString().toLongOrNull()
                val chance = chanceInput.text.toString().toIntOrNull()
                if (delay == null || delay < 0 || taps == null || taps < 1 ||
                    dist == null || dist < 10 || swipeMs == null || swipeMs < 50 ||
                    chance == null || chance !in 0..100
                ) {
                    Toast.makeText(
                        this, "대기 0 이상, 횟수 1 이상, 밀기 거리 10 이상, 밀기 시간 50 이상, 확률 0~100으로 입력하세요",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    point.action = selectedAction()
                    point.swipeDp = dist
                    point.swipeMs = swipeMs
                    point.chance = chance
                    apply(delay, taps)
                }
            }
            .setNeutralButton("기본값 ${config.defaultDelayMs}ms") { _, _ ->
                apply(config.defaultDelayMs, point.taps)
            }
            .setNegativeButton("취소", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        dialog.show()
    }

    private fun removeMarkers() {
        markers.forEach { wm.removeView(it.view) }
        markers.clear()
    }

    private fun rebuildMarkers() {
        removeMarkers()
        config.points.forEachIndexed { i, p -> markers.add(createMarker(i + 1, p)) }
        applyMarkerVisibility()
        updateInfo()
    }

    private fun savePositions() {
        markers.forEachIndexed { i, m ->
            config.points[i].x = m.params.x + markerPx / 2
            config.points[i].y = m.params.y + markerPx / 2
        }
        Store.save(this, config)
    }

    private fun setMarkersTouchable(touchable: Boolean) {
        markers.forEach { m ->
            m.params.flags = if (touchable) {
                m.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                m.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            m.view.alpha = if (touchable) 1f else 0.5f
            wm.updateViewLayout(m.view, m.params)
        }
    }

    private fun updateInfo() {
        if (!::infoText.isInitialized) return
        infoText.text = if (running) {
            val total = if (config.repeat == 0) "∞" else config.repeat.toString()
            val left = if (runEndAt > 0) {
                val min = ((runEndAt - SystemClock.uptimeMillis()).coerceAtLeast(0) + 59_999) / 60_000
                "\n남은 ${min / 60}:${"%02d".format(min % 60)}"
            } else {
                ""
            }
            "${cycle + 1}/$total$left"
        } else {
            val name = config.currentMacro.let { if (it.length > 6) it.take(5) + "…" else it }
            if (name.isEmpty()) "${config.points.size}개" else "${config.points.size}개\n$name"
        }
    }

    // ---------------------------------------------------------- point edits
    private fun addPoint() {
        if (running) return
        val dm = resources.displayMetrics
        val offset = dp(24) * (config.points.size % 6)
        // 직전 포인트의 대기 시간과 반복 횟수를 이어받는다 (첫 포인트는 기본값). 동작은 항상 터치.
        val last = config.points.lastOrNull()
        config.points.add(
            ClickPoint(
                dm.widthPixels / 2 + offset,
                dm.heightPixels / 2 + offset,
                last?.delayMs ?: config.defaultDelayMs,
                last?.taps ?: 1,
                chance = last?.chance ?: 100,
            )
        )
        Store.save(this, config)
        rebuildMarkers()
    }

    private fun removeLastPoint() {
        if (running || config.points.isEmpty()) return
        config.points.removeAt(config.points.lastIndex)
        Store.save(this, config)
        rebuildMarkers()
    }

    // --------------------------------------------------------------- macros
    private fun overlayDialog(builder: AlertDialog.Builder): AlertDialog =
        builder.create().apply { window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) }

    private val dialogTheme get() = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    /** 저장된 매크로 목록: 누르면 불러오기, 길게 누르면 삭제, 아래 버튼으로 현재 상태 저장. */
    private fun showMacroDialog() {
        if (running) return
        val names = Store.macroNames(this)
        val labels = names.map { if (it == config.currentMacro) "✓ $it" else it }.toTypedArray()
        val builder = AlertDialog.Builder(dialogTheme)
            .setTitle(if (names.isEmpty()) "저장된 매크로 없음" else "매크로 불러오기 (길게 누르면 삭제)")
            .setPositiveButton("현재 상태 저장") { _, _ -> showSaveMacroDialog() }
            .setNegativeButton("닫기", null)
        if (names.isEmpty()) {
            builder.setMessage("'현재 상태 저장'을 눌러 지금 포인트와 설정을 이름 붙여 저장하세요.")
        } else {
            builder.setItems(labels) { _, which -> loadMacro(names[which]) }
        }
        val dialog = overlayDialog(builder)
        dialog.show()
        dialog.listView?.setOnItemLongClickListener { _, _, position, _ ->
            dialog.dismiss()
            confirmDeleteMacro(names[position])
            true
        }
    }

    private fun showSaveMacroDialog() {
        val input = EditText(dialogTheme).apply {
            setSingleLine()
            setText(config.currentMacro.ifEmpty { "매크로 ${Store.macroNames(this@ClickService).size + 1}" })
            setSelectAllOnFocus(true)
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        val dialog = overlayDialog(
            AlertDialog.Builder(dialogTheme)
                .setTitle("매크로 이름")
                .setView(box)
                .setPositiveButton("저장") { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isEmpty()) {
                        Toast.makeText(this, "이름을 입력하세요", Toast.LENGTH_SHORT).show()
                    } else {
                        val existed = name in Store.macroNames(this)
                        config.currentMacro = name
                        Store.save(this, config)
                        Store.saveMacro(this, name, config)
                        updateInfo()
                        Toast.makeText(
                            this, if (existed) "'$name' 덮어써서 저장했습니다" else "'$name' 저장했습니다",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                .setNegativeButton("취소", null)
        )
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    private fun loadMacro(name: String) {
        if (running) return
        // 지금 매크로에서 바꾼 내용을 잃지 않도록 먼저 그 매크로에 자동 저장
        val current = config.currentMacro
        if (current.isNotEmpty() && current != name && current in Store.macroNames(this)) {
            Store.saveMacro(this, current, config)
        }
        if (!Store.applyMacro(this, name, config)) {
            Toast.makeText(this, "'$name'을(를) 불러오지 못했습니다", Toast.LENGTH_SHORT).show()
            return
        }
        Store.save(this, config)
        rebuildMarkers()
        Toast.makeText(this, "'$name' 불러왔습니다", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDeleteMacro(name: String) {
        overlayDialog(
            AlertDialog.Builder(dialogTheme)
                .setTitle("'$name' 삭제할까요?")
                .setPositiveButton("삭제") { _, _ ->
                    Store.deleteMacro(this, name)
                    if (config.currentMacro == name) {
                        config.currentMacro = ""
                        Store.save(this, config)
                        updateInfo()
                    }
                    Toast.makeText(this, "'$name' 삭제했습니다", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("취소", null)
        ).show()
    }

    private fun openSettings() {
        stop()
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // -------------------------------------------------------------- running
    private fun toggle() {
        when {
            running -> stop()
            // ■ 버튼을 누른 터치가 이미 '화면 터치 정지'를 일으켰다면 다시 시작하지 않는다
            SystemClock.uptimeMillis() - stoppedByTouchAt < 700 -> Unit
            else -> start()
        }
    }

    private fun stopByTouch() {
        if (!running) return
        stop()
        stoppedByTouchAt = SystemClock.uptimeMillis()
        Toast.makeText(this, "화면 터치로 정지했습니다", Toast.LENGTH_SHORT).show()
    }

    private fun start() {
        if (config.points.isEmpty()) {
            Toast.makeText(this, "＋ 버튼으로 터치할 위치를 먼저 추가하세요", Toast.LENGTH_SHORT).show()
            return
        }
        running = true
        index = 0
        tapRepeat = 0
        cycle = 0
        playBtn.text = buttonText("■", "정지")
        setMarkersTouchable(false)
        // 실행 중에는 마커가 움직이지 않으므로 터치 좌표를 한 번만 계산한다 (상태바/노치 오프셋 보정)
        targets = markers.map { m ->
            val loc = IntArray(2)
            m.view.getLocationOnScreen(loc)
            floatArrayOf((loc[0] + m.view.width / 2).toFloat(), (loc[1] + m.view.height / 2).toFloat())
        }
        if (config.stopOnTouch) addTouchCatchers()
        setKeepScreenOn(true) // 화면이 꺼지면 자동 터치가 멈추므로 실행 중에는 켜 둔다
        runEndAt = if (config.maxRunMinutes > 0) {
            SystemClock.uptimeMillis() + config.maxRunMinutes * 60_000L
        } else {
            0L
        }
        updateInfo()
        if (runEndAt > 0) scheduleTimeCheck()
        dispatchBatch()
    }

    /** 실행 시간 제한: 남은 시간 표시를 갱신하고, 시간이 다 되면 정지한다. */
    private fun scheduleTimeCheck() {
        handler.postDelayed({
            if (!running) return@postDelayed
            if (SystemClock.uptimeMillis() >= runEndAt) {
                stop()
                Toast.makeText(this, "설정한 실행 시간이 지나 정지했습니다", Toast.LENGTH_LONG).show()
            } else {
                updateInfo()
                scheduleTimeCheck()
            }
        }, minOf(TIME_CHECK_MS, (runEndAt - SystemClock.uptimeMillis()).coerceAtLeast(1)))
    }

    private fun setKeepScreenOn(on: Boolean) {
        val view = panel ?: return
        panelParams.flags = if (on) {
            panelParams.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            panelParams.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        wm.updateViewLayout(view, panelParams)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacksAndMessages(null)
        removeTouchCatchers()
        setKeepScreenOn(false)
        runEndAt = 0L
        stoppedAt = SystemClock.uptimeMillis()
        if (::playBtn.isInitialized) playBtn.text = buttonText("▶", "시작")
        markers.forEach { it.view.background = markerBackground(false) }
        // 이미 보낸 터치가 끝날 때까지 기다렸다가 마커를 다시 만질 수 있게 한다
        handler.postDelayed({ if (!running) setMarkersTouchable(true) }, STOP_GRACE_MS)
        applyMarkerVisibility()
        updateInfo()
    }

    /**
     * 실행 중 화면을 덮는 투명한 감지층. 터치할 위치마다 작은 구멍을 남겨 두어
     * 앱의 터치는 그대로 통과하고, 그 밖의 곳을 사용자가 누르면 정지한다.
     * 감지층은 여러 개의 사각형 창으로 "화면 전체 - 구멍들" 영역을 덮는다.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun addTouchCatchers() {
        removeTouchCatchers()
        val half = dp(HOLE_DP) / 2
        // 마커 창과 같은 좌표계(오버레이 배치 좌표)로 구멍을 만든다
        val holes = markers.map { m ->
            val cx = m.params.x + markerPx / 2
            val cy = m.params.y + markerPx / 2
            intArrayOf(cx - half, cy - half, cx + half, cy + half)
        }
        val dm = resources.displayMetrics
        val big = maxOf(dm.widthPixels, dm.heightPixels) * 3
        for (r in coverRects(holes, -big, -big, big, big)) {
            val view = View(this)
            view.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) stopByTouch()
                true
            }
            val params = overlayParams(r[0], r[1]).apply {
                width = r[2] - r[0]
                height = r[3] - r[1]
            }
            wm.addView(view, params)
            catchers.add(view)
        }
    }

    private fun removeTouchCatchers() {
        catchers.forEach { wm.removeView(it) }
        catchers.clear()
    }

    /** 사각형 영역 [l,t,r,b]에서 구멍들을 뺀 나머지를 덮는 사각형 목록 (가로 띠로 나눠 계산). */
    private fun coverRects(holes: List<IntArray>, l: Int, t: Int, r: Int, b: Int): List<IntArray> {
        val ys = (holes.flatMap { listOf(it[1], it[3]) } + listOf(t, b))
            .map { it.coerceIn(t, b) }.distinct().sorted()
        val out = mutableListOf<IntArray>()
        for (k in 0 until ys.size - 1) {
            val y0 = ys[k]
            val y1 = ys[k + 1]
            if (y1 <= y0) continue
            val inBand = holes.filter { it[1] < y1 && it[3] > y0 }.sortedBy { it[0] }
            var x = l
            for (h in inBand) {
                if (h[0] > x) out.add(intArrayOf(x, y0, h[0], y1))
                x = maxOf(x, h[2])
            }
            if (x < r) out.add(intArrayOf(x, y0, r, y1))
        }
        return out
    }

    /**
     * 다음 터치 여러 개를 하나의 제스처로 묶어 보낸다. 터치마다 따로 보내면 생기는
     * 지연이 사라져 연타가 빨라진다. 정지가 늦어지지 않도록 묶음 길이는 BATCH_WINDOW_MS로 제한한다.
     */
    private fun dispatchBatch() {
        if (!running) return
        val maxStrokes = minOf(MAX_BATCH_STROKES, GestureDescription.getMaxStrokeCount())
        val tapMs = config.tapDurationMs
        val builder = GestureDescription.Builder()
        val highlights = mutableListOf<Pair<Long, Int>>()
        var t = 0L
        var steps = 0
        var strokes = 0
        var lastStrokeEnd = 0L
        var finished = false
        while (strokes < maxStrokes && (steps == 0 || t <= BATCH_WINDOW_MS)) {
            val p = targets[index]
            val point = config.points[index]
            val path = Path().apply { moveTo(p[0], p[1]) }
            val duration = if (point.action == Action.TAP) {
                tapMs
            } else {
                // 밀기: 시작점에서 정한 방향으로 이동 (화면 밖으로 나가지 않게 제한)
                val d = dp(point.swipeDp).toFloat()
                val (dx, dy) = when (point.action) {
                    Action.SWIPE_UP -> 0f to -d
                    Action.SWIPE_DOWN -> 0f to d
                    Action.SWIPE_LEFT -> -d to 0f
                    else -> d to 0f
                }
                val dm = resources.displayMetrics
                path.lineTo(
                    (p[0] + dx).coerceIn(1f, dm.widthPixels - 1f),
                    (p[1] + dy).coerceIn(1f, dm.heightPixels - 1f),
                )
                point.swipeMs
            }
            // 확률에 따라 이번 동작을 실제로 할지 정한다. 건너뛰어도 시간은 그대로 흐른다.
            if (point.chance >= 100 || Random.nextInt(100) < point.chance) {
                builder.addStroke(GestureDescription.StrokeDescription(path, t, duration))
                highlights.add(t to index)
                strokes++
                lastStrokeEnd = t + duration
            }
            steps++
            // 다음 터치 위치로 진행하고, 이번 터치 뒤의 대기 시간을 구한다
            var gap = point.delayMs
            tapRepeat++
            if (tapRepeat >= point.taps) {
                tapRepeat = 0
                index++
                if (index >= targets.size) {
                    index = 0
                    cycle++
                    if (config.repeat in 1..cycle) {
                        finished = true
                    } else {
                        gap += config.loopDelayMs
                    }
                }
            }
            t += duration + gap
            if (finished) break
        }
        // 마지막으로 실제 실행한 동작이 끝난 뒤부터 이번 묶음이 끝날 때까지 남은 시간
        val waitAfter = t - lastStrokeEnd

        highlights.forEach { (at, i) ->
            handler.postDelayed({
                if (running) markers.forEachIndexed { k, m -> m.view.background = markerBackground(k == i) }
            }, at)
        }
        val afterBatch = {
            if (running) {
                updateInfo()
                if (finished) {
                    stop()
                    Toast.makeText(this@ClickService, "반복 완료", Toast.LENGTH_SHORT).show()
                } else {
                    dispatchBatch()
                }
            }
        }
        if (strokes == 0) {
            // 이번 묶음은 확률로 모두 건너뜀: 시간만 흘려보낸다
            handler.postDelayed({ afterBatch() }, t)
            return
        }
        val callback = object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                if (!running) return
                handler.postDelayed({ afterBatch() }, waitAfter)
            }

            override fun onCancelled(g: GestureDescription?) {
                if (!running) return
                // 앱이 터치하는 중에 사용자가 화면을 만지면 시스템이 앱의 터치를 취소한다
                if (config.stopOnTouch) {
                    stopByTouch()
                } else {
                    handler.postDelayed({ afterBatch() }, waitAfter)
                }
            }
        }
        if (!dispatchGesture(builder.build(), callback, handler)) {
            handler.postDelayed({ afterBatch() }, t.coerceAtLeast(16))
        }
    }
}
