package com.ensemble.scorelibrary.core.pedal

import android.content.Context
import android.view.KeyEvent
import com.ensemble.scorelibrary.core.model.PedalAction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject

val reservedPedalKeys = setOf(KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_POWER,
    KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP,
    KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)

fun defaultPedalKeys() = mapOf(
    KeyEvent.KEYCODE_PAGE_DOWN to PedalAction.NEXT_PAGE, KeyEvent.KEYCODE_DPAD_RIGHT to PedalAction.NEXT_PAGE,
    KeyEvent.KEYCODE_DPAD_DOWN to PedalAction.NEXT_PAGE, KeyEvent.KEYCODE_SPACE to PedalAction.NEXT_PAGE,
    KeyEvent.KEYCODE_PAGE_UP to PedalAction.PREVIOUS_PAGE, KeyEvent.KEYCODE_DPAD_LEFT to PedalAction.PREVIOUS_PAGE,
    KeyEvent.KEYCODE_DPAD_UP to PedalAction.PREVIOUS_PAGE, KeyEvent.KEYCODE_ENTER to PedalAction.TOGGLE_PLAY_MODE)

data class PedalConfiguration(val enabled: Boolean = true, val debounceMs: Long = 150,
    val profiles: Map<String, Map<Int, PedalAction>> = mapOf("default" to defaultPedalKeys())) {
    init {
        require(debounceMs in 50..600 && profiles.size in 1..32 && "default" in profiles)
        require(profiles.all { (name, keys) -> name.length <= 100 && keys.size <= 32 && keys.all { (code, action) ->
            code in 1..KeyEvent.getMaxKeyCode() && code !in reservedPedalKeys && action != PedalAction.NO_OP } })
    }
    fun mapping(profile: String) = profiles[profile] ?: profiles.getValue("default")
}

object PedalConfigurationCodec {
    fun encode(configuration: PedalConfiguration) = JSONObject().apply {
        put("enabled", configuration.enabled); put("debounce", configuration.debounceMs)
        put("profiles", JSONObject().apply { configuration.profiles.forEach { (profile, map) ->
            put(profile, JSONObject().apply { map.forEach { (key, action) -> put(key.toString(), action.name) } })
        } })
    }.toString()
    fun decode(text: String): PedalConfiguration {
        require(text.length <= 100_000)
        val root = JSONObject(text); val profiles = root.getJSONObject("profiles")
        val result = profiles.keys().asSequence().associateWith { name ->
            val keys = profiles.getJSONObject(name)
            keys.keys().asSequence().associate { key -> key.toInt() to PedalAction.valueOf(keys.getString(key)) }
        }
        return PedalConfiguration(root.getBoolean("enabled"), root.getLong("debounce"), result)
    }
}

interface PedalSettingsStore {
    suspend fun load(): PedalConfiguration
    suspend fun save(configuration: PedalConfiguration)
}

class AndroidPedalSettingsStore @Inject constructor(@param:ApplicationContext context: Context) : PedalSettingsStore {
    private val preferences = context.getSharedPreferences("pedal-settings", Context.MODE_PRIVATE)
    override suspend fun load() = withContext(Dispatchers.IO) {
        preferences.getString("configuration", null)?.let(PedalConfigurationCodec::decode) ?: PedalConfiguration()
    }
    override suspend fun save(configuration: PedalConfiguration) = withContext(Dispatchers.IO) {
        check(preferences.edit().putString("configuration", PedalConfigurationCodec.encode(configuration)).commit()) { "脚踏配置保存失败" }
    }
}

data class PedalDispatch(val consumed: Boolean, val action: PedalAction = PedalAction.NO_OP)

class PedalEventGate {
    private val held = mutableSetOf<Pair<Int, Int>>()
    private val last = mutableMapOf<Pair<Int, Int>, Long>()
    fun release(device: Int, code: Int) = held.remove(device to code)
    fun down(event: KeyEvent, time: Long, debounce: Long): Boolean {
        val key = event.deviceId to event.keyCode
        val first = held.add(key)
        if (!first || event.repeatCount > 0 || event.isCanceled) return false
        val previous = last[key]
        if (previous != null && time - previous < debounce) return false
        last[key] = time
        return true
    }
    fun clearDevice(device: Int) { held.removeAll { it.first == device }; last.keys.removeAll { it.first == device } }
    fun clear() { held.clear(); last.clear() }
}
