package com.uvm.autoclicker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 한 번 터치할 때 누르고 있는 시간의 초기값. 너무 짧으면 일부 앱/게임이 터치를 놓친다. */
const val DEFAULT_TAP_DURATION_MS = 30L

/** "새 포인트 기본 대기 시간"의 초기값 (설정 화면에서 바꿀 수 있음) */
const val DEFAULT_DELAY_MS = 150L

/** 포인트 동작: 터치 또는 한 방향으로 밀기(스크롤) */
enum class Action(val label: String, val arrow: String) {
    TAP("터치", ""),
    SWIPE_UP("위로 밀기 (목록을 아래로 내림)", "↑"),
    SWIPE_DOWN("아래로 밀기 (목록을 위로 올림)", "↓"),
    SWIPE_LEFT("왼쪽으로 밀기", "←"),
    SWIPE_RIGHT("오른쪽으로 밀기", "→"),
}

const val DEFAULT_SWIPE_DP = 300
const val DEFAULT_SWIPE_MS = 400L

/**
 * 터치할 위치(화면 좌표, 마커 중심), 이 위치를 연속으로 터치할 횟수,
 * 매 터치 후 대기 시간. 동작이 밀기이면 이 위치에서 시작해 swipeDp만큼 swipeMs 동안 민다.
 */
data class ClickPoint(
    var x: Int,
    var y: Int,
    var delayMs: Long = DEFAULT_DELAY_MS,
    var taps: Int = 1,
    var action: Action = Action.TAP,
    var swipeDp: Int = DEFAULT_SWIPE_DP,
    var swipeMs: Long = DEFAULT_SWIPE_MS,
)

data class Config(
    val points: MutableList<ClickPoint> = mutableListOf(),
    /** 전체 순서 반복 횟수. 0 = 무한 */
    var repeat: Int = 0,
    /** 한 바퀴가 끝난 뒤 다음 반복까지 대기 시간 */
    var loopDelayMs: Long = 0,
    /** ＋로 새로 추가하는 포인트의 대기 시간 */
    var defaultDelayMs: Long = DEFAULT_DELAY_MS,
    /** 실행 중 사용자가 화면을 터치하면 정지 */
    var stopOnTouch: Boolean = true,
    /** 한 번 터치할 때 누르고 있는 시간 */
    var tapDurationMs: Long = DEFAULT_TAP_DURATION_MS,
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
                ClickPoint(
                    p.getInt("x"),
                    p.getInt("y"),
                    p.optLong("delayMs", DEFAULT_DELAY_MS),
                    p.optInt("taps", 1).coerceAtLeast(1),
                    Action.entries.firstOrNull { it.name == p.optString("action") } ?: Action.TAP,
                    p.optInt("swipeDp", DEFAULT_SWIPE_DP),
                    p.optLong("swipeMs", DEFAULT_SWIPE_MS),
                )
            }
            Config(
                points,
                o.optInt("repeat", 0),
                o.optLong("loopDelayMs", 0),
                o.optLong("defaultDelayMs", DEFAULT_DELAY_MS),
                o.optBoolean("stopOnTouch", true),
                o.optLong("tapDurationMs", DEFAULT_TAP_DURATION_MS).coerceIn(1, 2000),
            )
        } catch (e: Exception) {
            Config()
        }
    }

    fun save(ctx: Context, cfg: Config) {
        val arr = JSONArray()
        cfg.points.forEach {
            arr.put(
                JSONObject().put("x", it.x).put("y", it.y).put("delayMs", it.delayMs).put("taps", it.taps)
                    .put("action", it.action.name).put("swipeDp", it.swipeDp).put("swipeMs", it.swipeMs)
            )
        }
        val o = JSONObject()
            .put("points", arr)
            .put("repeat", cfg.repeat)
            .put("loopDelayMs", cfg.loopDelayMs)
            .put("defaultDelayMs", cfg.defaultDelayMs)
            .put("stopOnTouch", cfg.stopOnTouch)
            .put("tapDurationMs", cfg.tapDurationMs)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }
}
