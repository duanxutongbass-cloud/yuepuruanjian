package com.ensemble.scorelibrary.core.pedal

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ensemble.scorelibrary.core.model.PedalAction
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import javax.inject.Inject

data class PedalDevice(val id: Int, val profile: String, val name: String)
data class PedalState(val configuration: PedalConfiguration? = null, val devices: List<PedalDevice> = emptyList(),
    val selected: String = "default", val busy: Boolean = true, val learning: PedalAction? = null,
    val testing: Boolean = false, val message: String? = null, val settingsOpen: Boolean = false)

@HiltViewModel
class PedalViewModel @Inject constructor(@param:ApplicationContext context: Context,
    private val store: PedalSettingsStore) : ViewModel(), InputManager.InputDeviceListener {
    private val mutable = MutableStateFlow(PedalState())
    val state = mutable.asStateFlow()
    private val manager = context.getSystemService(InputManager::class.java)
    private val gate = PedalEventGate()
    init {
        manager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        devices()
        viewModelScope.launch {
            try { mutable.value = mutable.value.copy(configuration = store.load(), busy = false) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = mutable.value.copy(busy = false, message = "脚踏配置不可用，请恢复默认配置") }
        }
    }
    private fun profile(device: InputDevice) = "device-" + MessageDigest.getInstance("SHA-256")
        .digest(device.descriptor.toByteArray()).joinToString("") { "%02x".format(it) }
    // API 26-28 has no public external flag: conservatively accept identifiable keyboard HID devices.
    private fun external(device: InputDevice): Boolean = if (android.os.Build.VERSION.SDK_INT >= 29) device.isExternal
        else !device.isVirtual && (device.vendorId != 0 || device.productId != 0) &&
            device.supportsSource(InputDevice.SOURCE_KEYBOARD)
    private fun devices() {
        val devices = InputDevice.getDeviceIds().toList().mapNotNull(InputDevice::getDevice).filter(::external)
            .map { PedalDevice(it.id, profile(it), it.name) }
        mutable.value = mutable.value.copy(devices = devices)
    }
    override fun onInputDeviceAdded(deviceId: Int) { devices() }
    override fun onInputDeviceChanged(deviceId: Int) { devices() }
    override fun onInputDeviceRemoved(deviceId: Int) {
        val external = mutable.value.devices.any { it.id == deviceId }
        gate.clearDevice(deviceId); devices()
        if (external) mutable.value = mutable.value.copy(message = "输入设备已断开，触屏仍可翻页")
    }
    fun settings(open: Boolean) {
        mutable.value = mutable.value.copy(settingsOpen = open, learning = null, testing = false)
    }
    fun select(profile: String) { mutable.value = mutable.value.copy(selected = profile, learning = null, testing = false) }
    fun learn(action: PedalAction) { require(action != PedalAction.NO_OP)
        if (!mutable.value.busy) mutable.value = mutable.value.copy(learning = action, testing = false, message = "请按下要学习的脚踏按键") }
    fun test(enabled: Boolean) { mutable.value = mutable.value.copy(testing = enabled, learning = null, message = null) }
    fun enable(enabled: Boolean) { mutable.value.configuration?.let { save(it.copy(enabled = enabled)) } }
    fun debounce(value: Long) { mutable.value.configuration?.let { save(it.copy(debounceMs = value.coerceIn(50, 600))) } }
    fun clear(action: PedalAction) {
        val state = mutable.value; val configuration = state.configuration ?: return
        if (!canAddProfile(configuration)) return
        val map = configuration.mapping(state.selected).filterValues { it != action }
        save(configuration.copy(profiles = configuration.profiles + (state.selected to map)))
    }
    fun reset() {
        val state = mutable.value; val configuration = state.configuration ?: PedalConfiguration()
        if (!canAddProfile(configuration)) return
        save(configuration.copy(profiles = configuration.profiles + (state.selected to defaultPedalKeys())))
    }
    fun feedback(message: String) { mutable.value = mutable.value.copy(message = message) }
    fun removeProfile() {
        val state = mutable.value; val config = state.configuration ?: return
        if (state.selected != "default") save(config.copy(profiles = config.profiles - state.selected))
    }
    private fun canAddProfile(config: PedalConfiguration): Boolean {
        val allowed = mutable.value.selected in config.profiles || config.profiles.size < 32
        if (!allowed) feedback("已达设备配置上限，请清除旧设备配置")
        return allowed
    }
    fun resetInput() { gate.clear() }
    fun handle(event: KeyEvent, readerActive: Boolean, blocked: Boolean, now: Long = SystemClock.uptimeMillis()): PedalDispatch {
        if (event.action == KeyEvent.ACTION_UP) return PedalDispatch(gate.release(event.deviceId, event.keyCode))
        if (event.action != KeyEvent.ACTION_DOWN || event.isCanceled) return PedalDispatch(false)
        val device = InputDevice.getDevice(event.deviceId)
        if (device != null && !external(device) && !device.isVirtual || device == null && event.deviceId >= 0) return PedalDispatch(false)
        val state = mutable.value
        val capture = state.settingsOpen && (state.learning != null || state.testing)
        val configuration = state.configuration
        if ((!readerActive && !capture) || event.keyCode in reservedPedalKeys || event.keyCode !in 1..KeyEvent.getMaxKeyCode()) return PedalDispatch(false)
        val source = device?.let(::profile) ?: "default"
        val action = configuration?.mapping(if (capture) state.selected else source)?.get(event.keyCode) ?: PedalAction.NO_OP
        if (!capture && configuration?.enabled == false) return PedalDispatch(false)
        if (!capture && action == PedalAction.NO_OP) {
            if (event.keyCode !in defaultPedalKeys()) return PedalDispatch(false)
            gate.down(event, now, configuration?.debounceMs ?: 150)
            feedback(if (configuration == null) "脚踏配置不可用，请检查设置" else "按键未映射")
            return PedalDispatch(true)
        }
        if (!gate.down(event, now, configuration?.debounceMs ?: 150)) return PedalDispatch(true)
        if (state.busy) return PedalDispatch(true)
        if (capture) {
            if (state.selected != "default" && state.selected != source) {
                feedback("收到其他设备按键，请使用当前选中的设备")
                return PedalDispatch(true)
            }
            mutable.value = mutable.value.copy(message = "${device?.name ?: "虚拟键盘"} · ${KeyEvent.keyCodeToString(event.keyCode)} · ${actionLabel(action)}")
            state.learning?.let { target ->
                val config = configuration ?: PedalConfiguration()
                if (!canAddProfile(config)) return PedalDispatch(true)
                val mapping = config.mapping(state.selected)
                val existing = mapping[event.keyCode]
                if (existing != null && existing != target) {
                    feedback("按键已用于${actionLabel(existing)}，请先清除该映射")
                } else {
                    val next = mapping.filterValues { it != target } + (event.keyCode to target)
                    save(config.copy(profiles = config.profiles + (state.selected to next)))
                }
            }
            return PedalDispatch(true)
        }
        if (blocked) { feedback("批注操作进行中，请稍后翻页"); return PedalDispatch(true) }
        return PedalDispatch(true, action)
    }
    private fun save(configuration: PedalConfiguration) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true)
        viewModelScope.launch {
            try {
                store.save(configuration)
                mutable.value = mutable.value.copy(configuration = configuration, busy = false, learning = null, message = "脚踏配置已保存")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = mutable.value.copy(busy = false, message = "脚踏配置保存失败，请重试") }
        }
    }
    override fun onCleared() { manager.unregisterInputDeviceListener(this); gate.clear() }
}

fun actionLabel(action: PedalAction) = when (action) {
    PedalAction.NEXT_PAGE -> "下一页"
    PedalAction.PREVIOUS_PAGE -> "上一页"
    PedalAction.TOGGLE_PLAY_MODE -> "演出流切换"
    PedalAction.NO_OP -> "未映射"
}
