package com.uvm.autoclicker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 새 포인트의 기본 "터치 후 대기 시간" */
const val DEFAULT_DELAY_MS = 150L

/** 터치할 위치(화면 좌표, 마커 중심)와 터치 후 다음 포인트까지 대기 시간. */
data class ClickPoint(var x: Int, var y: Int, var delayMs: Long = DEFAULT_DELAY_MS)

data class Config(
    val points: MutableList<ClickPoint> = mutableListOf(),
    /** 전체 순서 반복 횟수. 0 = 무한 */
    var repeat: Int = 0,
    /** 한 바퀴가 끝난 뒤 다음 반복까지 대기 시간 */
    var loopDelayMs: Long = 0,
)

object Store {
    private const val PREFS = "autoclicker"
    private const val KEY = "config"

    fun load(ctx: Context): Config {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return Config()
        return try {
            val o = JSONObject(raw)
            val arr = o.optJSONArray("points") ?: JSONArray()
            val points = MutableList(arr.length()) { i ->
                val p = arr.getJSONObject(i)
                ClickPoint(p.getInt("x"), p.getInt("y"), p.optLong("delayMs", DEFAULT_DELAY_MS))
            }
            Config(points, o.optInt("repeat", 0), o.optLong("loopDelayMs", 0))
        } catch (e: Exception) {
            Config()
        }
    }

    fun save(ctx: Context, cfg: Config) {
        val arr = JSONArray()
        cfg.points.forEach {
            arr.put(JSONObject().put("x", it.x).put("y", it.y).put("delayMs", it.delayMs))
        }
        val o = JSONObject()
            .put("points", arr)
            .put("repeat", cfg.repeat)
            .put("loopDelayMs", cfg.loopDelayMs)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }
}
