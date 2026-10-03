package com.uvm.autoclicker

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases의 최신 버전을 확인하고, 새 APK를 내려받아 PackageInstaller로 설치한다.
 * 릴리스 태그는 "v<versionCode>" 형식이며 .apk 파일이 첨부되어 있어야 한다.
 */
object Updater {
    private const val REPO = "akskekekdk/autoclicker"

    data class Release(val versionCode: Long, val tag: String, val apkUrl: String)

    fun currentVersionCode(ctx: Context): Long {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    fun currentVersionName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** 네트워크 작업이므로 백그라운드 스레드에서 호출. 실패 시 예외. */
    fun fetchLatest(): Release? {
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            if (conn.responseCode == 404) return null // 아직 릴리스 없음
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name")
            val code = tag.removePrefix("v").toLongOrNull() ?: return null
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    return Release(code, tag, a.getString("browser_download_url"))
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** APK를 내려받아 설치 세션에 쓰고 커밋한다. 백그라운드 스레드에서 호출. */
    fun downloadAndInstall(ctx: Context, url: String, onProgress: (Int) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            val installer = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("update.apk", 0, total).use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress((done * 100 / total).toInt())
                        }
                    }
                    session.fsync(out)
                }
                val flags = if (Build.VERSION.SDK_INT >= 31) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pending = PendingIntent.getBroadcast(
                    ctx, sessionId, Intent(ctx, InstallReceiver::class.java), flags,
                )
                session.commit(pending.intentSender)
            }
        } finally {
            conn.disconnect()
        }
    }
}

/** 설치 세션 결과 수신: 사용자 확인이 필요하면 시스템 설치 화면을 띄운다. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "알 수 없는 오류"
                Toast.makeText(context, "업데이트 실패: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
