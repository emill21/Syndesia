package com.syndesia.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class Appearance(
    val themeMode: String = "system", // system | light | dark
    val pureBlack: Boolean = false,   // true black in dark mode, for OLED
    val accent: Int = 0,              // 0 = wallpaper
    val background: String = "aurora",
) {
    fun isDark(systemDark: Boolean) = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }
}

/** A watch we've connected to before. Its port changes whenever Wireless debugging restarts. */
data class SavedWatch(
    val host: String,
    val port: Int,
    val model: String,
    val serial: String,
    val lastSeen: Long,
)

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("syndesia", Context.MODE_PRIVATE)

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    var lastSeenVersion: Int
        get() = sp.getInt("last_seen_version", 0)
        set(v) = sp.edit().putInt("last_seen_version", v).apply()

    var autoConnect: Boolean
        get() = sp.getBoolean("auto_connect", true)
        set(v) = sp.edit().putBoolean("auto_connect", v).apply()

    var allowDowngrade: Boolean
        get() = sp.getBoolean("downgrade", false)
        set(v) = sp.edit().putBoolean("downgrade", v).apply()

    var grantPermissions: Boolean
        get() = sp.getBoolean("grant", false)
        set(v) = sp.edit().putBoolean("grant", v).apply()

    var showHidden: Boolean
        get() = sp.getBoolean("hidden", false)
        set(v) = sp.edit().putBoolean("hidden", v).apply()

    var showSystemApps: Boolean
        get() = sp.getBoolean("system_apps", false)
        set(v) = sp.edit().putBoolean("system_apps", v).apply()

    var installs: Int
        get() = sp.getInt("installs", 0)
        set(v) = sp.edit().putInt("installs", v).apply()

    /** Most recent first. */
    var watches: List<SavedWatch>
        get() = runCatching {
            val arr = JSONArray(sp.getString("watches", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SavedWatch(o.getString("h"), o.getInt("p"), o.optString("m", "Watch"), o.optString("s"), o.optLong("t"))
            }
        }.getOrDefault(emptyList())
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(JSONObject().put("h", it.host).put("p", it.port).put("m", it.model).put("s", it.serial).put("t", it.lastSeen)) }
            sp.edit().putString("watches", arr.toString()).apply()
        }

    /** Remember a watch after connecting. Same serial (or same address) replaces the old entry. */
    fun remember(w: SavedWatch) {
        watches = listOf(w) + watches.filterNot { (w.serial.isNotBlank() && it.serial == w.serial) || it.host == w.host }
    }

    fun forget(w: SavedWatch) {
        watches = watches.filterNot { it.host == w.host && it.serial == w.serial }
    }

    var appearance: Appearance
        get() = Appearance(
            themeMode = sp.getString("theme", "system")!!,
            pureBlack = sp.getBoolean("pure_black", false),
            accent = sp.getInt("accent", 0),
            background = sp.getString("bg", "aurora")!!,
        )
        set(v) = sp.edit().putString("theme", v.themeMode).putBoolean("pure_black", v.pureBlack)
            .putInt("accent", v.accent).putString("bg", v.background).apply()
}

/** App-wide observable appearance, so the theme updates instantly from the picker. */
object Look {
    private val _state = MutableStateFlow(Appearance())
    val state: StateFlow<Appearance> = _state.asStateFlow()
    private var loaded = false

    fun init(context: Context) {
        if (!loaded) { _state.value = Prefs(context).appearance; loaded = true }
    }

    fun update(context: Context, a: Appearance) {
        _state.value = a
        Prefs(context).appearance = a
    }
}
