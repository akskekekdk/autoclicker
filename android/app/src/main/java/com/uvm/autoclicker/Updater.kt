package com.uvm.autoclicker

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases의 최신 버전을 확인하고, 새 APK를 내려받아 시스템 설치 화면으로 연다.
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

    /** 최신 릴리스 페이지 (브라우저로 직접 받을 때). */
    const val RELEASES_URL = "https://github.com/$REPO/releases/latest"

    private const val APK_MIME = "application/vnd.android.package-archive"

    /** APK를 앱 캐시에 내려받는다. 백그라운드 스레드에서 호출. */
    fun download(ctx: Context, url: String, onProgress: (Int) -> Unit): File {
        val file = ApkProvider.apkFile(ctx)
        file.parentFile?.mkdirs()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
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
            }
        } finally {
            conn.disconnect()
        }
        return file
    }

    /** 내려받은 APK를 시스템 설치 화면으로 연다 (파일 관리자에서 APK를 누른 것과 같은 방식). */
    fun openInstaller(activity: Activity) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(ApkProvider.uri(activity), APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }

    fun openReleasePage(activity: Activity) {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)))
    }
}

/** 내려받은 업데이트 APK 하나를 설치 프로그램에 읽기 전용으로 넘겨주는 최소 ContentProvider. */
class ApkProvider : ContentProvider() {
    companion object {
        private const val FILE_NAME = "update.apk"

        fun apkFile(ctx: Context) = File(File(ctx.cacheDir, "updates"), FILE_NAME)

        fun uri(ctx: Context): Uri = Uri.parse("content://${ctx.packageName}.apk/$FILE_NAME")
    }

    override fun onCreate() = true

    override fun getType(uri: Uri) = "application/vnd.android.package-archive"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (uri.lastPathSegment != FILE_NAME) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(apkFile(context!!), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor {
        // 설치 프로그램이 파일 이름/크기를 물어볼 때 응답
        val file = apkFile(context!!)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any>(FILE_NAME, file.length()))
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ) = 0
}
