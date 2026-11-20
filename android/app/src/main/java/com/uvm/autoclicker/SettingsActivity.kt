package com.uvm.autoclicker

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.view.View
import android.widget.RadioGroup
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast

/** 설정 화면 (패널의 ⚙ 또는 접근성이 꺼져 있을 때): 권한 안내, 반복 설정, 포인트별 대기 시간 편집. UI는 코드로 구성. */
class SettingsActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var panelBtn: Button
    private lateinit var repeatEdit: EditText
    private lateinit var loopDelayEdit: EditText
    private lateinit var sessionsBox: LinearLayout
    private val sessionRows = mutableListOf<Triple<EditText, EditText, EditText>>() // 시, 분, 초
    private val sessionRepeatEdits = mutableListOf<EditText>()
    private lateinit var keepAliveEdit: EditText
    private lateinit var startHoursEdit: EditText
    private lateinit var startMinutesEdit: EditText
    private lateinit var startSecondsEdit: EditText
    private lateinit var limitHoursEdit: EditText
    private lateinit var limitMinutesEdit: EditText
    private lateinit var pointsBox: LinearLayout

    private lateinit var updateText: TextView
    private lateinit var updateBtn: Button
    private var latest: Updater.Release? = null
    private var updating = false

    private var config = Config()
    private lateinit var defaultDelayEdit: EditText
    private lateinit var stopModeGroup: RadioGroup
    private lateinit var highlightCheck: CheckBox
    private lateinit var maxSpeedCheck: CheckBox
    private lateinit var swipeDebugCheck: CheckBox
    private lateinit var randomEdit: EditText
    private lateinit var tapDurationEdit: EditText
    private val delayEdits = mutableListOf<EditText>()
    private val tapsEdits = mutableListOf<EditText>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 알림 권한은 처음 들어왔을 때 한 번만 요청 (onResume에 두면 팝업이 반복돼 화면이 깜빡였음)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(28))
            setBackgroundColor(Color.parseColor("#EEF1F5"))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#EEF1F5"))
            addView(root)
        })

        // 제목
        root.addView(TextView(this).apply {
            text = "오토 클리커 설정"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A237E"))
            setPadding(dp(4), dp(4), 0, dp(12))
        })

        // 앱 업데이트
        card("앱 업데이트").apply {
            updateText = text("현재 버전 ${Updater.currentVersionName(this@SettingsActivity)}")
            addView(updateText)
            updateBtn = button("업데이트 확인") { onUpdateClicked() }
            addView(updateBtn)
            addView(button("브라우저로 받기") { Updater.openReleasePage(this@SettingsActivity) })
        }

        // 접근성 권한
        card("접근성 권한").apply {
            statusText = text("").apply { setTypeface(typeface, Typeface.BOLD) }
            addView(statusText)
            addView(hint(
                "화면을 대신 터치하려면 접근성 서비스를 켜야 합니다.\n" +
                    "설정 → 접근성 → 설치된 앱 → '오토 클리커' → 사용.\n" +
                    "※ 회색으로 막혀 있으면: 앱 정보 → ⋮ → '제한된 설정 허용' 후 다시 시도."
            ))
            addView(button("접근성 설정 열기") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
            panelBtn = button("플로팅 패널 열기") { togglePanel() }
            addView(panelBtn)
        }

        // 실행 설정
        card("실행 설정").apply {
            repeatEdit = numberEdit()
            loopDelayEdit = numberEdit()
            addView(row("반복 횟수 (0 = 무한)", repeatEdit))
            addView(row("반복 사이 대기 (ms)", loopDelayEdit))
            limitHoursEdit = numberEdit()
            limitMinutesEdit = numberEdit()
            addView(hmsLabelRow("실행 시간 제한", listOf(limitHoursEdit to "시간", limitMinutesEdit to "분")))
            startHoursEdit = numberEdit()
            startMinutesEdit = numberEdit()
            startSecondsEdit = numberEdit()
            addView(hmsLabelRow("시작 대기", listOf(startHoursEdit to "시", startMinutesEdit to "분", startSecondsEdit to "초")))
            addView(hint("반복 횟수와 실행 시간 중 먼저 끝나는 쪽에서 정지합니다. 시작 대기는 ▶ 뒤 그 시간이 지나야 시작합니다 (0 = 바로). 실행 중에는 화면이 꺼지지 않습니다."))
        }

        // 속도 / 터치
        card("속도 · 터치").apply {
            tapDurationEdit = numberEdit()
            addView(row("터치 누르는 시간 (ms)", tapDurationEdit))
            defaultDelayEdit = numberEdit()
            addView(row("첫 포인트 기본 대기 (ms)", defaultDelayEdit))
            maxSpeedCheck = CheckBox(this@SettingsActivity).apply { text = "최고 속도 모드 (더 빠름 · 정지 버튼 느려짐)" }
            addView(maxSpeedCheck)
            randomEdit = numberEdit()
            addView(row("랜덤 딜레이 ±% (0 = 끔)", randomEdit))
            addView(hint("터치 누르는 시간이 작을수록 연타가 빨라집니다. 가끔 안 먹히면 50~100으로 늘리세요. 최고 속도 모드에서는 볼륨 키로 정지하세요. 랜덤 딜레이 ±%는 각 대기 시간을 그 범위에서 무작위로 흔듭니다 (예: 30이면 100ms가 70~130ms)."))
        }

        // 정지 · 표시
        card("정지 · 표시").apply {
            addView(text("정지 방법").apply { setTypeface(typeface, Typeface.BOLD) })
            stopModeGroup = RadioGroup(this@SettingsActivity)
            StopMode.entries.forEach { mode ->
                stopModeGroup.addView(RadioButton(this@SettingsActivity).apply {
                    id = View.generateViewId()
                    text = mode.label
                    tag = mode
                })
            }
            addView(stopModeGroup)
            highlightCheck = CheckBox(this@SettingsActivity).apply { text = "실행 중 포인트 강조 표시 (끄면 깜빡임 없음)" }
            addView(highlightCheck)
            swipeDebugCheck = CheckBox(this@SettingsActivity).apply { text = "밀기 진단 표시 (밀기가 완료/취소/실패인지 알림)" }
            addView(swipeDebugCheck)
            addView(hint("실행 중 볼륨 버튼(또는 볼륨 변경)으로 언제든 정지할 수 있습니다. 밀기(스크롤)가 동작하지 않을 때 '밀기 진단 표시'를 켜면 밀기 때마다 완료/취소/실패가 뜹니다."))
        }

        // 예약 세션
        card("예약 세션").apply {
            addView(hint("▶을 누른 시점 기준으로 정한 시간마다 매크로 전체를 '횟수'만큼 실행합니다. 예: 3시간 뒤 10회. 세션이 있으면 '시작 대기'는 무시됩니다."))
            sessionsBox = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(sessionsBox)
            addView(button("세션 추가") { addSessionRow(0, 0, 0, 1) })
            keepAliveEdit = numberEdit()
            addView(row("대기 중 유지 탭 간격 (초, 0 = 끔)", keepAliveEdit))
            addView(hint("대기 동안 이 간격마다 1번 포인트를 눌러 게임이 끊기는 것을 막습니다. 1번 포인트는 빈 곳에 두세요. (모든 매크로 공통)"))
        }

        // 포인트 목록
        card("포인트 목록").apply {
            addView(hint("각 포인트의 대기 시간과 연속 터치 횟수. 위치·동작·순서는 화면의 원을 탭해서 바꾸세요."))
            pointsBox = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(pointsBox)
        }

        // 저장
        root.addView(button("설정 저장") {
            if (save()) Toast.makeText(this, "저장했습니다", Toast.LENGTH_SHORT).show()
        }.apply {
            setBackgroundColor(Color.parseColor("#3D5AFE"))
            setTextColor(Color.WHITE)
            textSize = 16f
        })

        // 사용 방법 (맨 아래)
        card("사용 방법").apply {
            addView(hint(
                "플로팅 패널 버튼\n" +
                    "  ⠿ 끌어서 이동   ▶/■ 시작/정지\n" +
                    "  ＋ 포인트 추가 (빨간 원을 원하는 곳으로)\n" +
                    "  － 마지막 포인트 삭제\n" +
                    "  🔁 전체 반복 횟수   ⏰ 예약   💾 매크로 저장/불러오기\n" +
                    "  ⚙ 설정   ⇄/⇅ 가로·세로   ▲/▼ 접기·펼치기   ✕ 닫기\n" +
                    "빨간 원 탭: 순서(◀▶)·삭제·동작·대기·횟수·확률·그룹·예약·종결 설정.\n" +
                    "검정 원 = 종결/종료: 전체 반복이 끝난 뒤 한 번 실행.\n" +
                    "번호 순서대로, 각 포인트를 '횟수'만큼 누르고 매 터치 후 '대기'만큼 기다립니다."
            ))
        }

        checkUpdate()
    }

    // ------------------------------------------------------------- updates
    private fun checkUpdate() {
        updateText.text = "현재 버전 ${Updater.currentVersionName(this)} · 확인 중..."
        Thread {
            val result = runCatching { Updater.fetchLatest() }
            runOnUiThread {
                val current = Updater.currentVersionName(this)
                val release = result.getOrNull()
                latest = release
                when {
                    result.isFailure -> {
                        updateText.text = "현재 버전 $current · 업데이트 확인 실패 (인터넷 연결 확인)"
                        updateBtn.text = "업데이트 확인"
                    }
                    release != null && release.versionCode > Updater.currentVersionCode(this) -> {
                        updateText.text = "현재 버전 $current · 새 버전 ${release.tag} 있음"
                        updateBtn.text = "업데이트 설치 (${release.tag})"
                    }
                    else -> {
                        updateText.text = "현재 버전 $current · 최신 버전입니다"
                        updateBtn.text = "업데이트 확인"
                    }
                }
            }
        }.start()
    }

    private fun onUpdateClicked() {
        if (updating) return
        val release = latest
        if (release == null || release.versionCode <= Updater.currentVersionCode(this)) {
            checkUpdate()
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "'이 출처 허용'을 켠 뒤 다시 눌러주세요", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }
        updating = true
        updateBtn.isEnabled = false
        Thread {
            val result = runCatching {
                Updater.download(this, release.apkUrl) { pct ->
                    runOnUiThread { updateText.text = "다운로드 중... $pct%" }
                }
            }
            runOnUiThread {
                updating = false
                updateBtn.isEnabled = true
                if (result.isSuccess) {
                    updateText.text = "설치 화면에서 '업데이트'를 눌러주세요"
                    runCatching { Updater.openInstaller(this) }.onFailure {
                        updateText.text = "설치 화면을 열지 못했습니다: ${it.message}\n'브라우저로 받기'를 눌러 직접 설치하세요"
                    }
                } else {
                    updateText.text = "다운로드 실패: ${result.exceptionOrNull()?.message}\n'브라우저로 받기'를 눌러 직접 설치하세요"
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        config = Store.load(this)
        repeatEdit.setText(config.repeat.toString())
        loopDelayEdit.setText(config.loopDelayMs.toString())
        sessionsBox.removeAllViews()
        sessionRows.clear()
        sessionRepeatEdits.clear()
        config.sessions.forEach { addSessionRow(it.startSec / 3600, it.startSec / 60 % 60, it.startSec % 60, it.repeats) }
        keepAliveEdit.setText(config.keepAliveSec.toString())
        startHoursEdit.setText((config.startDelaySec / 3600).toString())
        startMinutesEdit.setText((config.startDelaySec / 60 % 60).toString())
        startSecondsEdit.setText((config.startDelaySec % 60).toString())
        limitHoursEdit.setText((config.maxRunMinutes / 60).toString())
        limitMinutesEdit.setText((config.maxRunMinutes % 60).toString())
        defaultDelayEdit.setText(config.defaultDelayMs.toString())
        for (i in 0 until stopModeGroup.childCount) {
            val rb = stopModeGroup.getChildAt(i) as RadioButton
            if (rb.tag == config.stopMode) rb.isChecked = true
        }
        highlightCheck.isChecked = config.highlightRun
        maxSpeedCheck.isChecked = config.maxSpeed
        swipeDebugCheck.isChecked = config.swipeDebug
        randomEdit.setText(config.randomPercent.toString())
        tapDurationEdit.setText(config.tapDurationMs.toString())
        renderPoints()
        renderStatus()
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    private fun renderStatus() {
        val service = ClickService.instance
        if (service == null) {
            statusText.text = "상태: 접근성 서비스 꺼짐"
            statusText.setTextColor(Color.parseColor("#C62828"))
            panelBtn.isEnabled = false
        } else {
            statusText.text = "상태: 접근성 서비스 켜짐"
            statusText.setTextColor(Color.parseColor("#2E7D32"))
            panelBtn.isEnabled = true
            panelBtn.text = if (service.isPanelShown) "플로팅 패널 닫기" else "플로팅 패널 열기"
        }
    }

    private fun togglePanel() {
        val service = ClickService.instance ?: return
        save()
        if (service.isPanelShown) {
            service.hidePanel()
        } else {
            service.showPanel()
            moveTaskToBack(true) // 패널을 바로 쓸 수 있도록 앱을 뒤로 보낸다
        }
        renderStatus()
    }

    private fun renderPoints() {
        pointsBox.removeAllViews()
        delayEdits.clear()
        tapsEdits.clear()
        if (config.points.isEmpty()) {
            pointsBox.addView(text("등록된 포인트가 없습니다. 플로팅 패널의 ＋ 로 추가하세요."))
            return
        }
        config.points.forEachIndexed { i, p ->
            val edit = numberEdit().apply { setText(p.delayMs.toString()) }
            delayEdits.add(edit)
            val tapsEdit = numberEdit().apply { setText(p.taps.toString()) }
            tapsEdits.add(tapsEdit)
            val del = Button(this).apply {
                text = "삭제"
                setOnClickListener {
                    save()
                    config.points.removeAt(i)
                    Store.save(this@SettingsActivity, config)
                    ClickService.instance?.reload()
                    renderPoints()
                }
            }
            val tag = if (p.scheduled) "  [예약]" else ""
            pointsBox.addView(text("#${i + 1}  (${p.x}, ${p.y})$tag").apply {
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(8), 0, 0)
            })
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@SettingsActivity).apply { text = "대기(ms)" })
                addView(edit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@SettingsActivity).apply { text = " 횟수" })
                addView(tapsEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f))
                addView(del)
            }
            pointsBox.addView(line)
        }
    }

    /** 입력값을 검증해 저장. 잘못된 값이 있으면 false. */
    private fun addSessionRow(h: Int, m: Int, s: Int, repeats: Int) {
        val he = numberEdit().apply { setText(h.toString()) }
        val me = numberEdit().apply { setText(m.toString()) }
        val se = numberEdit().apply { setText(s.toString()) }
        val re = numberEdit().apply { setText(repeats.toString()) }
        sessionRows.add(Triple(he, me, se))
        sessionRepeatEdits.add(re)
        lateinit var row: LinearLayout
        val del = Button(this).apply {
            text = "삭제"
            setOnClickListener {
                val idx = sessionRows.indexOfFirst { it.first === he }
                if (idx >= 0) {
                    sessionRows.removeAt(idx); sessionRepeatEdits.removeAt(idx)
                }
                sessionsBox.removeView(row)
            }
        }
        row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            listOf(he to "시", me to "분", se to "초").forEach { (e, u) ->
                addView(e, LinearLayout.LayoutParams(dp(42), LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(TextView(this@SettingsActivity).apply { text = u })
            }
            addView(TextView(this@SettingsActivity).apply { text = "  " })
            addView(re, LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(TextView(this@SettingsActivity).apply { text = "회 " })
            addView(del)
        }
        sessionsBox.addView(row)
    }

    private fun save(): Boolean {
        val repeat = repeatEdit.text.toString().toIntOrNull()
        val loopDelay = loopDelayEdit.text.toString().toLongOrNull()
        val startH = startHoursEdit.text.toString().ifBlank { "0" }.toIntOrNull()
        val startM = startMinutesEdit.text.toString().ifBlank { "0" }.toIntOrNull()
        val startS = startSecondsEdit.text.toString().ifBlank { "0" }.toIntOrNull()
        if (startH == null || startM == null || startS == null || startH < 0 || startM < 0 || startS < 0) {
            Toast.makeText(this, "시작 대기는 0 이상의 숫자로 입력하세요", Toast.LENGTH_SHORT).show()
            return false
        }
        val limitHours = limitHoursEdit.text.toString().ifBlank { "0" }.toIntOrNull()
        val limitMinutes = limitMinutesEdit.text.toString().ifBlank { "0" }.toIntOrNull()
        if (limitHours == null || limitHours < 0 || limitMinutes == null || limitMinutes < 0) {
            Toast.makeText(this, "실행 시간 제한은 0 이상의 숫자로 입력하세요", Toast.LENGTH_SHORT).show()
            return false
        }
        val defaultDelay = defaultDelayEdit.text.toString().toLongOrNull()
        val tapDuration = tapDurationEdit.text.toString().toLongOrNull()
        if (tapDuration == null || tapDuration !in 1..2000) {
            Toast.makeText(this, "터치 누르는 시간은 1~2000 사이로 입력하세요", Toast.LENGTH_SHORT).show()
            return false
        }
        val delays = delayEdits.map { it.text.toString().toLongOrNull() }
        val taps = tapsEdits.map { it.text.toString().toIntOrNull() }
        if (repeat == null || repeat < 0 || loopDelay == null || loopDelay < 0 ||
            defaultDelay == null || defaultDelay < 0 ||
            delays.any { it == null || it < 0 } || taps.any { it == null || it < 1 }
        ) {
            Toast.makeText(this, "숫자를 올바르게 입력하세요 (대기 0 이상, 횟수 1 이상)", Toast.LENGTH_SHORT).show()
            return false
        }
        // 서비스에서 마커를 옮겼을 수 있으므로 최신 좌표를 다시 읽어 대기값만 덮어쓴다
        val latest = Store.load(this)
        if (latest.points.size == delays.size) {
            latest.points.forEachIndexed { i, p ->
                p.delayMs = delays[i]!!
                p.taps = taps[i]!!
            }
        }
        latest.repeat = repeat
        latest.loopDelayMs = loopDelay
        latest.maxRunMinutes = limitHours * 60 + limitMinutes
        latest.startDelaySec = startH * 3600 + startM * 60 + startS
        val newSessions = mutableListOf<RunSession>()
        for (i in sessionRows.indices) {
            val (h, m, sec) = sessionRows[i]
            val hv = h.text.toString().ifBlank { "0" }.toIntOrNull()
            val mv = m.text.toString().ifBlank { "0" }.toIntOrNull()
            val sv = sec.text.toString().ifBlank { "0" }.toIntOrNull()
            val rep = sessionRepeatEdits[i].text.toString().ifBlank { "1" }.toIntOrNull()
            if (hv == null || mv == null || sv == null || rep == null || hv < 0 || mv < 0 || sv < 0 || rep < 1) {
                Toast.makeText(this, "예약 세션: 시간은 0 이상, 횟수는 1 이상으로 입력하세요", Toast.LENGTH_SHORT).show()
                return false
            }
            newSessions.add(RunSession(hv * 3600 + mv * 60 + sv, rep))
        }
        newSessions.sortBy { it.startSec }
        latest.sessions = newSessions
        val keepAlive = keepAliveEdit.text.toString().ifBlank { "0" }.toIntOrNull()
        if (keepAlive == null || keepAlive < 0) {
            Toast.makeText(this, "유지 탭 간격은 0 이상의 숫자로 입력하세요", Toast.LENGTH_SHORT).show()
            return false
        }
        latest.keepAliveSec = keepAlive
        latest.defaultDelayMs = defaultDelay
        latest.stopMode = (stopModeGroup.findViewById<RadioButton>(stopModeGroup.checkedRadioButtonId)?.tag as? StopMode)
            ?: StopMode.ANY_TOUCH
        latest.highlightRun = highlightCheck.isChecked
        latest.maxSpeed = maxSpeedCheck.isChecked
        latest.swipeDebug = swipeDebugCheck.isChecked
        latest.randomPercent = (randomEdit.text.toString().ifBlank { "0" }.toIntOrNull() ?: 0).coerceIn(0, 100)
        latest.tapDurationMs = tapDuration
        Store.save(this, latest)
        config = latest
        ClickService.instance?.reload()
        return true
    }

    // ------------------------------------------------------------- widgets
    /** 섹션 카드를 만들어 root에 추가하고 반환한다. */
    private fun card(titleText: String): LinearLayout {
        val header = TextView(this).apply {
            text = titleText
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#3D5AFE"))
            setPadding(0, 0, 0, dp(8))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
            addView(header)
        }
        root.addView(box)
        return box
    }

    /** 카드 안의 설명(회색 작은 글씨). */
    private fun hint(s: String) = TextView(this).apply {
        text = s
        textSize = 13f
        setTextColor(Color.parseColor("#777777"))
        setPadding(0, dp(4), 0, dp(2))
    }

    /** '라벨  [입력]단위 [입력]단위' 한 줄. */
    private fun hmsLabelRow(label: String, fields: List<Pair<EditText, String>>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@SettingsActivity).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        fields.forEach { (e, unit) ->
            addView(e, LinearLayout.LayoutParams(dp(46), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(TextView(this@SettingsActivity).apply { text = " $unit " })
        }
    }

    private fun title(s: String) = TextView(this).apply {
        text = s
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun text(s: String) = TextView(this).apply {
        text = s
        textSize = 14f
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        setOnClickListener { onClick() }
    }

    private fun numberEdit() = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine()
    }

    private fun row(label: String, edit: EditText) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@SettingsActivity).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(edit, LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT))
    }
}
