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
    /** 동작이 실제로 실행될 확률(%). 실행하지 않아도 시간은 그대로 흐른다 */
    var chance: Int = 100,
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
    /** 최대 실행 시간(분). 0 = 제한 없음. 반복 횟수와 둘 중 먼저 도달하면 정지 */
    var maxRunMinutes: Int = 0,
    /** 플로팅 패널을 가로로 배치 */
    var panelHorizontal: Boolean = false,
    /** 마지막으로 저장하거나 불러온 매크로 이름 (없으면 빈 문자열) */
    var currentMacro: String = "",
)

object Store {
    private const val PREFS = "autoclicker"
    private const val KEY = "config"
    private const val MACROS_KEY = "macros"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): Config {
        val raw = prefs(ctx).getString(KEY, null) ?: return Config()
        return try {
            fromJson(JSONObject(raw))
        } catch (e: Exception) {
            Config()
        }
    }

    fun save(ctx: Context, cfg: Config) {
        prefs(ctx).edit().putString(KEY, toJson(cfg).toString()).apply()
    }

    // --------------------------------------------------------------- macros
    // 매크로 = 포인트 목록과 실행 설정(반복 횟수·반복 사이 대기·실행 시간 제한·누르는 시간)의 이름 붙은 사본.
    // 화면 터치 정지, 패널 방향 같은 앱 전체 설정은 매크로에 포함하지 않는다.

    private fun macros(ctx: Context): JSONObject =
        try {
            JSONObject(prefs(ctx).getString(MACROS_KEY, null) ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }

    fun macroNames(ctx: Context): List<String> = macros(ctx).keys().asSequence().toList().sorted()

    fun saveMacro(ctx: Context, name: String, cfg: Config) {
        val all = macros(ctx).put(name, toJson(cfg))
        prefs(ctx).edit().putString(MACROS_KEY, all.toString()).apply()
    }

    fun deleteMacro(ctx: Context, name: String) {
        val all = macros(ctx).apply { remove(name) }
        prefs(ctx).edit().putString(MACROS_KEY, all.toString()).apply()
    }

    /** 저장된 매크로의 포인트와 실행 설정을 cfg에 덮어쓴다. 없으면 false. */
    fun applyMacro(ctx: Context, name: String, cfg: Config): Boolean {
        val o = macros(ctx).optJSONObject(name) ?: return false
        val m = try {
            fromJson(o)
        } catch (e: Exception) {
            return false
        }
        cfg.points.clear()
        cfg.points.addAll(m.points)
        cfg.repeat = m.repeat
        cfg.loopDelayMs = m.loopDelayMs
        cfg.maxRunMinutes = m.maxRunMinutes
        cfg.tapDurationMs = m.tapDurationMs
        cfg.currentMacro = name
        return true
    }

    // ----------------------------------------------------------------- json
    private fun fromJson(o: JSONObject): Config {
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
                p.optInt("chance", 100).coerceIn(0, 100),
            )
        }
        return Config(
            points,
            o.optInt("repeat", 0),
            o.optLong("loopDelayMs", 0),
            o.optLong("defaultDelayMs", DEFAULT_DELAY_MS),
            o.optBoolean("stopOnTouch", true),
            o.optLong("tapDurationMs", DEFAULT_TAP_DURATION_MS).coerceIn(1, 2000),
            o.optInt("maxRunMinutes", 0).coerceAtLeast(0),
            o.optBoolean("panelHorizontal", false),
            o.optString("currentMacro", ""),
        )
    }

    private fun toJson(cfg: Config): JSONObject {
        val arr = JSONArray()
        cfg.points.forEach {
            arr.put(
                JSONObject().put("x", it.x).put("y", it.y).put("delayMs", it.delayMs).put("taps", it.taps)
                    .put("action", it.action.name).put("swipeDp", it.swipeDp).put("swipeMs", it.swipeMs)
                    .put("chance", it.chance)
            )
        }
        return JSONObject()
            .put("points", arr)
            .put("repeat", cfg.repeat)
            .put("loopDelayMs", cfg.loopDelayMs)
            .put("defaultDelayMs", cfg.defaultDelayMs)
            .put("stopOnTouch", cfg.stopOnTouch)
            .put("tapDurationMs", cfg.tapDurationMs)
            .put("maxRunMinutes", cfg.maxRunMinutes)
            .put("panelHorizontal", cfg.panelHorizontal)
            .put("currentMacro", cfg.currentMacro)
    }
}
