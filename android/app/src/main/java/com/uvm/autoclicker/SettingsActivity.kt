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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 설정 화면 (패널의 ⚙ 또는 접근성이 꺼져 있을 때): 권한 안내, 반복 설정, 포인트별 대기 시간 편집. UI는 코드로 구성. */
class SettingsActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var panelBtn: Button
    private lateinit var repeatEdit: EditText
    private lateinit var loopDelayEdit: EditText
    private lateinit var pointsBox: LinearLayout

    private lateinit var updateText: TextView
    private lateinit var updateBtn: Button
    private var latest: Updater.Release? = null
    private var updating = false

    private var config = Config()
    private lateinit var defaultDelayEdit: EditText
    private val delayEdits = mutableListOf<EditText>()
    private val tapsEdits = mutableListOf<EditText>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(title("앱 업데이트"))
        updateText = text("현재 버전 ${Updater.currentVersionName(this)}")
        root.addView(updateText)
        updateBtn = button("업데이트 확인") { onUpdateClicked() }
        root.addView(updateBtn)
        root.addView(button("브라우저로 받기") { Updater.openReleasePage(this) })

        root.addView(title("1. 접근성 권한"))
        root.addView(text(
            "화면을 대신 터치하려면 접근성 서비스를 켜야 합니다.\n" +
                "설정 → 접근성 → 설치된 앱 → '오토 클리커' → 사용.\n" +
                "※ 회색으로 막혀 있으면: 설정 → 앱 → 오토 클리커 → 우측 상단 ⋮ → '제한된 설정 허용' 후 다시 시도하세요."
        ))
        statusText = text("").apply { setTypeface(typeface, Typeface.BOLD) }
        root.addView(statusText)
        root.addView(button("접근성 설정 열기") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        panelBtn = button("플로팅 패널 열기") { togglePanel() }
        root.addView(panelBtn)

        root.addView(title("2. 사용 방법"))
        root.addView(text(
            "플로팅 패널\n" +
                "  ⠿ 끌어서 패널 이동\n" +
                "  ▶ / ■ 시작 / 정지\n" +
                "  ＋ 터치 위치 추가 (빨간 원을 원하는 곳으로 끌어다 놓기)\n" +
                "  빨간 원 탭: 그 포인트의 대기 시간(ms)과 연속 터치 횟수 입력\n" +
                "  － 마지막 위치 삭제\n" +
                "  ⚙ 이 설정 화면 열기\n" +
                "  ▲ 접기 / ▼ 펼치기 (같은 버튼을 다시 누르면 펼쳐짐)\n" +
                "  ✕ 패널 닫기 (앱 아이콘을 누르면 다시 열림)\n" +
                "번호 순서대로 터치합니다. 각 포인트를 '횟수'만큼 연속 터치하고, 매 터치 후 '대기'만큼 기다립니다."
        ))

        root.addView(title("3. 반복 설정"))
        repeatEdit = numberEdit()
        loopDelayEdit = numberEdit()
        root.addView(row("반복 횟수 (0 = 무한)", repeatEdit))
        root.addView(row("반복 사이 대기 (ms)", loopDelayEdit))
        defaultDelayEdit = numberEdit()
        root.addView(row("새 포인트 기본 대기 (ms)", defaultDelayEdit))

        root.addView(title("4. 포인트별 대기 시간 / 터치 횟수"))
        pointsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(pointsBox)

        root.addView(button("저장") {
            if (save()) Toast.makeText(this, "저장했습니다", Toast.LENGTH_SHORT).show()
        })

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
        defaultDelayEdit.setText(config.defaultDelayMs.toString())
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
            pointsBox.addView(text("#${i + 1}  (${p.x}, ${p.y})").apply {
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
    private fun save(): Boolean {
        val repeat = repeatEdit.text.toString().toIntOrNull()
        val loopDelay = loopDelayEdit.text.toString().toLongOrNull()
        val defaultDelay = defaultDelayEdit.text.toString().toLongOrNull()
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
        latest.defaultDelayMs = defaultDelay
        Store.save(this, latest)
        config = latest
        ClickService.instance?.reload()
        return true
    }

    // ------------------------------------------------------------- widgets
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
