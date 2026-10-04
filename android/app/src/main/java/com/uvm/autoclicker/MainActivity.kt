package com.uvm.autoclicker

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * 앱 아이콘을 눌렀을 때 실행되는 보이지 않는 화면.
 * 접근성 서비스가 켜져 있으면 플로팅 패널만 열고 바로 닫힌다 (설정 화면은 패널의 ⚙로 연다).
 * 꺼져 있으면 권한을 켤 수 있도록 설정 화면을 연다.
 *
 * 홈 화면 아이콘이 유지되도록 런처 컴포넌트 이름(MainActivity)은 바꾸지 않는다.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = ClickService.instance
        if (service != null) {
            service.showPanel()
        } else {
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }
}
