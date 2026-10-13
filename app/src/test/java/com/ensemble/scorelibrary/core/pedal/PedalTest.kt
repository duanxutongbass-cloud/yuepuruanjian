package com.ensemble.scorelibrary.core.pedal

import android.content.Context
import android.view.KeyEvent
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.core.model.PedalAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PedalTest {
    private val dispatcher = StandardTestDispatcher()
    private val views = ViewModelStore()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var config = PedalConfiguration()
    private var failLoad = false
    private var failSave = false
    private val store = object : PedalSettingsStore {
        override suspend fun load(): PedalConfiguration { check(!failLoad); return config }
        override suspend fun save(configuration: PedalConfiguration) { check(!failSave); config = configuration }
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { views.clear(); Dispatchers.resetMain() }
    private fun model() = PedalViewModel(context, store).also { views.put("pedal", it) }
    private fun event(code: Int = KeyEvent.KEYCODE_PAGE_DOWN, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0) =
        KeyEvent(0, 0, action, code, repeat, 0, -1, 0)

    @Test fun heldRepeatedBounceAndIndependentDevicesAreFiltered() {
        val gate = PedalEventGate()
        assertTrue(gate.down(event(), 1000, 150))
        assertFalse(gate.down(event(), 1200, 150))
        assertFalse(gate.down(event(repeat = 1), 1500, 150))
        assertTrue(gate.release(-1, KeyEvent.KEYCODE_PAGE_DOWN))
        assertFalse(gate.down(event(), 1100, 150))
        gate.release(-1, KeyEvent.KEYCODE_PAGE_DOWN)
        assertTrue(gate.down(event(), 1200, 150))
        val other = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, 0, 0, -2, 0)
        assertTrue(gate.down(other, 1200, 150))
        gate.clearDevice(-1)
        assertTrue(gate.down(event(), 1201, 150))
        assertFalse(gate.down(other, 1400, 150))
    }
    @Test fun configurationRoundTripsAndRejectsSystemKeysAndUnknownActions() {
        val expected = PedalConfiguration(false, 300, mapOf("default" to emptyMap(), "device-x" to mapOf(KeyEvent.KEYCODE_F1 to PedalAction.NEXT_PAGE)))
        assertEquals(expected, PedalConfigurationCodec.decode(PedalConfigurationCodec.encode(expected)))
        assertThrows(IllegalArgumentException::class.java) { PedalConfiguration(debounceMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { PedalConfiguration(profiles = mapOf("default" to mapOf(KeyEvent.KEYCODE_BACK to PedalAction.NEXT_PAGE))) }
        assertThrows(IllegalArgumentException::class.java) { PedalConfigurationCodec.decode("{\"enabled\":true,\"debounce\":150,\"profiles\":{\"default\":{\"1\":\"UNKNOWN\"}}}") }
    }
    @Test fun realStoreSurvivesNewInstance() = runTest(dispatcher) {
        val expected = PedalConfiguration(debounceMs = 400)
        AndroidPedalSettingsStore(context).save(expected)
        assertEquals(expected, AndroidPedalSettingsStore(context).load())
        context.getSharedPreferences("pedal-settings", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun outsideReaderDisabledReservedAndUnknownDeviceDoNotIntercept() = runTest(dispatcher) {
        val vm = model(); runCurrent()
        assertFalse(vm.handle(event(), false, false, 1000).consumed)
        assertFalse(vm.handle(event(KeyEvent.KEYCODE_BACK), true, false, 1000).consumed)
        assertFalse(vm.handle(KeyEvent(0, 0, 0, KeyEvent.KEYCODE_PAGE_DOWN, 0, 0, 123456, 0), true, false, 1000).consumed)
        vm.enable(false); runCurrent()
        assertFalse(vm.handle(event(), true, false, 1000).consumed)
    }
    @Test fun learningTestAndConflictsNeverFlipPages() = runTest(dispatcher) {
        val vm = model(); runCurrent(); vm.settings(true); vm.learn(PedalAction.NEXT_PAGE)
        assertEquals(PedalAction.NO_OP, vm.handle(event(KeyEvent.KEYCODE_PAGE_UP), false, false, 1000).action)
        assertTrue(vm.state.value.message!!.contains("请先清除"))
        vm.handle(event(KeyEvent.KEYCODE_PAGE_UP, KeyEvent.ACTION_UP), false, false)
        vm.handle(event(KeyEvent.KEYCODE_F1), false, false, 1200); runCurrent()
        assertEquals(PedalAction.NEXT_PAGE, config.mapping("default")[KeyEvent.KEYCODE_F1])
        assertNull(config.mapping("default")[KeyEvent.KEYCODE_PAGE_DOWN])
        vm.handle(event(KeyEvent.KEYCODE_F1, KeyEvent.ACTION_UP), false, false)
        vm.test(true)
        assertEquals(PedalAction.NO_OP, vm.handle(event(KeyEvent.KEYCODE_F1), true, false, 1400).action)
        vm.handle(event(KeyEvent.KEYCODE_F1, KeyEvent.ACTION_UP), false, false)
        vm.settings(false)
        assertEquals(PedalAction.NEXT_PAGE, vm.handle(event(KeyEvent.KEYCODE_F1), true, false, 1600).action)
    }
    @Test fun annotationBusyAndSaveFailureCannotChangeMapping() = runTest(dispatcher) {
        val vm = model(); runCurrent()
        assertEquals(PedalAction.NO_OP, vm.handle(event(), true, true, 1000).action)
        assertTrue(vm.state.value.message!!.contains("批注操作"))
        vm.handle(event(action = KeyEvent.ACTION_UP), true, false)
        failSave = true; vm.clear(PedalAction.NEXT_PAGE); runCurrent()
        assertEquals(defaultPedalKeys(), vm.state.value.configuration!!.mapping("default"))
        assertTrue(vm.state.value.message!!.contains("保存失败"))
    }
    @Test fun failedLoadIsClosedUntilExplicitReset() = runTest(dispatcher) {
        failLoad = true; val vm = model(); runCurrent()
        assertNull(vm.state.value.configuration)
        assertEquals(PedalAction.NO_OP, vm.handle(event(), true, false, 1000).action)
        vm.reset(); runCurrent()
        assertEquals(PedalConfiguration(), vm.state.value.configuration)
    }
    @Test fun learningDifferentDeviceCannotOverwriteSelectedProfile() = runTest(dispatcher) {
        val vm = model(); runCurrent(); vm.settings(true); vm.select("device-selected")
        vm.learn(PedalAction.NEXT_PAGE)
        vm.handle(event(KeyEvent.KEYCODE_F1), false, false, 1000); runCurrent()
        assertTrue(vm.state.value.message!!.contains("其他设备"))
        assertFalse("device-selected" in config.profiles)
    }
    @Test fun canceledDownNeverExecutesAndPauseClearsHeldKeys() = runTest(dispatcher) {
        val vm = model(); runCurrent()
        val canceled = KeyEvent.changeFlags(event(), KeyEvent.FLAG_CANCELED)
        assertFalse(vm.handle(canceled, true, false, 1000).consumed)
        assertEquals(PedalAction.NEXT_PAGE, vm.handle(event(), true, false, 1100).action)
        assertEquals(PedalAction.NO_OP, vm.handle(event(), true, false, 1400).action)
        vm.resetInput()
        assertEquals(PedalAction.NEXT_PAGE, vm.handle(event(), true, false, 1401).action)
    }
}
