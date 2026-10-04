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
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * 접근성 서비스: 플로팅 컨트롤 패널과 번호가 붙은 위치 마커를 화면 위에 띄우고,
 * 실행 시 마커 위치를 순서대로 터치(dispatchGesture)합니다.
 */
class ClickService : AccessibilityService() {

    companion object {
        var instance: ClickService? = null
            private set

        private const val MARKER_DP = 48
        private const val TAP_DURATION_MS = 40L
        /** 앱이 보낸 터치가 끝난 뒤 이 시간 안의 터치 신호는 앱 자신의 것으로 본다 */
        private const val OWN_TOUCH_GRACE_MS = 60L
    }

    private class Marker(val view: TextView, val params: WindowManager.LayoutParams)

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var config = Config()
    private val markers = mutableListOf<Marker>()

    private var panel: View? = null
    private lateinit var expandedView: LinearLayout
    private lateinit var foldBtn: TextView
    private var foldable: List<View> = emptyList()
    private var minimized = false
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var playBtn: TextView
    private lateinit var infoText: TextView

    private var running = false

    // 화면 터치 감지: 1px 크기의 보이지 않는 창이 화면 어디든 터치되면 ACTION_OUTSIDE를 받는다.
    // 앱이 직접 보낸 터치도 같은 신호를 만들므로, 그 시간대의 신호는 무시한다.
    private var touchWatcher: View? = null
    private var gestureInFlight = false
    private var lastGestureEnd = 0L
    private var stoppedByTouchAt = 0L
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
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(28))
        }
        playBtn = panelButton("▶", "시작") { toggle() }
        infoText = TextView(this).apply {
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            layoutParams = LinearLayout.LayoutParams(dp(52), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        foldBtn = panelButton("▲", "접기") { setMinimized(!minimized) }
        // 접었을 때 숨길 버튼들 (핸들과 접기/펼치기 버튼은 항상 보임)
        foldable = listOf(
            playBtn,
            panelButton("＋", "추가") { addPoint() },
            panelButton("－", "삭제") { removeLastPoint() },
            panelButton("⚙", "설정") { openSettings() },
            panelButton("✕", "닫기") { hidePanel() },
            infoText,
        )

        expandedView.addView(handle)
        foldable.dropLast(1).forEach { expandedView.addView(it) }
        expandedView.addView(foldBtn)
        expandedView.addView(infoText)

        panelParams = overlayParams(0, resources.displayMetrics.heightPixels / 4)
        makeDraggable(handle, expandedView, panelParams)
        wm.addView(expandedView, panelParams)
        panel = expandedView
        setMinimized(false)
        updateInfo()
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
        val detail = if (point.taps > 1) "${point.delayMs}×${point.taps}" else "${point.delayMs}"
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
        val point = config.points[index]
        val theme = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        fun numberInput(value: Number) = EditText(theme).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value.toString())
            setSelectAllOnFocus(true)
        }
        fun label(s: String) = TextView(theme).apply { text = s }
        val delayInput = numberInput(point.delayMs)
        val tapsInput = numberInput(point.taps)
        val box = LinearLayout(theme).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(label("터치 후 대기 시간 (ms)"))
            addView(delayInput)
            addView(label("이 위치 연속 터치 횟수"))
            addView(tapsInput)
        }
        val apply = { delay: Long, taps: Int ->
            point.delayMs = delay
            point.taps = taps
            Store.save(this, config)
            markers.getOrNull(index)?.view?.text = markerText(index + 1, point)
        }
        val dialog = AlertDialog.Builder(theme)
            .setTitle("${index + 1}번 포인트")
            .setView(box)
            .setPositiveButton("저장") { _, _ ->
                val delay = delayInput.text.toString().toLongOrNull()
                val taps = tapsInput.text.toString().toIntOrNull()
                if (delay == null || delay < 0 || taps == null || taps < 1) {
                    Toast.makeText(this, "대기는 0 이상, 횟수는 1 이상으로 입력하세요", Toast.LENGTH_SHORT).show()
                } else {
                    apply(delay, taps)
                }
            }
            .setNeutralButton("기본값 ${config.defaultDelayMs}ms") { _, _ ->
                apply(config.defaultDelayMs, point.taps)
            }
            .setNegativeButton("취소", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        delayInput.requestFocus()
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
            "${cycle + 1}/$total"
        } else {
            "${config.points.size}개"
        }
    }

    // ---------------------------------------------------------- point edits
    private fun addPoint() {
        if (running) return
        val dm = resources.displayMetrics
        val offset = dp(24) * (config.points.size % 6)
        config.points.add(ClickPoint(dm.widthPixels / 2 + offset, dm.heightPixels / 2 + offset, config.defaultDelayMs))
        Store.save(this, config)
        rebuildMarkers()
    }

    private fun removeLastPoint() {
        if (running || config.points.isEmpty()) return
        config.points.removeAt(config.points.lastIndex)
        Store.save(this, config)
        rebuildMarkers()
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
        if (config.stopOnTouch) addTouchWatcher()
        updateInfo()
        tapCurrent()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addTouchWatcher() {
        val view = View(this)
        val params = overlayParams(0, 0).apply {
            width = 1
            height = 1
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        view.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE && running && !isOwnTouch()) stopByTouch()
            false
        }
        wm.addView(view, params)
        touchWatcher = view
    }

    private fun removeTouchWatcher() {
        touchWatcher?.let { wm.removeView(it) }
        touchWatcher = null
    }

    /** 앱이 보낸 터치가 진행 중이거나 막 끝난 직후인지 */
    private fun isOwnTouch() =
        gestureInFlight || SystemClock.uptimeMillis() - lastGestureEnd < OWN_TOUCH_GRACE_MS

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacksAndMessages(null)
        removeTouchWatcher()
        gestureInFlight = false
        if (::playBtn.isInitialized) playBtn.text = buttonText("▶", "시작")
        markers.forEach { it.view.background = markerBackground(false) }
        setMarkersTouchable(true)
        applyMarkerVisibility()
        updateInfo()
    }

    private fun tapCurrent() {
        if (!running) return
        val marker = markers[index]
        // 실제 화면상의 마커 중심을 터치한다 (상태바/노치 오프셋 보정)
        val loc = IntArray(2)
        marker.view.getLocationOnScreen(loc)
        val cx = (loc[0] + marker.view.width / 2).toFloat()
        val cy = (loc[1] + marker.view.height / 2).toFloat()

        markers.forEachIndexed { i, m -> m.view.background = markerBackground(i == index) }

        val path = Path().apply { moveTo(cx, cy) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
            .build()
        val delay = config.points[index].delayMs
        val callback = object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                gestureEnded()
                scheduleNext(delay)
            }

            override fun onCancelled(g: GestureDescription?) {
                gestureEnded()
                // 사용자가 화면을 만지면 시스템이 앱의 터치를 취소한다
                if (config.stopOnTouch && running) {
                    stopByTouch()
                } else {
                    scheduleNext(delay)
                }
            }
        }
        gestureInFlight = true
        if (!dispatchGesture(gesture, callback, handler)) {
            gestureEnded()
            scheduleNext(delay)
        }
    }

    private fun gestureEnded() {
        gestureInFlight = false
        lastGestureEnd = SystemClock.uptimeMillis()
    }

    private fun scheduleNext(delayMs: Long) {
        if (!running) return
        handler.postDelayed({ next() }, delayMs)
    }

    private fun next() {
        if (!running) return
        tapRepeat++
        if (tapRepeat < config.points[index].taps) {
            tapCurrent() // 같은 위치를 지정한 횟수만큼 반복
            return
        }
        tapRepeat = 0
        index++
        if (index < markers.size) {
            tapCurrent()
            return
        }
        index = 0
        cycle++
        if (config.repeat in 1..cycle) {
            stop()
            Toast.makeText(this, "반복 완료", Toast.LENGTH_SHORT).show()
            return
        }
        updateInfo()
        handler.postDelayed({ tapCurrent() }, config.loopDelayMs)
    }
}
