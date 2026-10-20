package com.ensemble.scorelibrary.reading

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.spec.SecretKeySpec

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReadingShelfViewModelTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = SecretKeySpec(ByteArray(32) { 14 }, "AES")
    private val storage get() = ReadingActivityStore(temporary.root) { key }
    private val dispatcher = StandardTestDispatcher()
    private val worker = CoroutineScope(SupervisorJob() + dispatcher)
    private val models = ViewModelStore()
    private var member = "a"
    private var valid = true
    private val epoch = MutableStateFlow(0)
    private val activity by lazy { ReadingActivity(storage, { m -> ReadingIdentity(ReadingOwner("local", m)) {
        check(valid && m == member)
    } }, epoch.map { Unit }, worker) }
    private fun model() = ReadingShelfViewModel(activity).also { models.put("shelf", it) }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { models.clear(); worker.cancel(); Dispatchers.resetMain() }
    private suspend fun settled(vm: ReadingShelfViewModel, condition: (ReadingShelfState) -> Boolean = { true }) {
        withContext(Dispatchers.Default) { withTimeout(10000) { vm.state.first { !it.busy && condition(it) } } }
    }
    @Test fun newViewModelRestoresDataAndConfirmedHistoryClearKeepsFavorite() = runTest(dispatcher) {
        val owner = ReadingOwner("local", "a")
        storage.record(owner, RecentReading("p", "part", "a".repeat(64), 2))
        var vm = model(); vm.bind(member); settled(vm) { it.member == member }
        vm.favorite(member, "p", true); settled(vm) { "p" in it.data.favorites }
        models.clear(); vm = model(); vm.bind(member); settled(vm) { it.data.recent.isNotEmpty() }
        vm.clearRecent(member); settled(vm) { it.data.recent.isEmpty() }
        assertEquals(setOf("p"), vm.state.value.data.favorites)
        assertEquals(vm.state.value.data, storage.read(owner))
    }
    @Test fun switchClearsSynchronouslyAndOldButtonsCannotModifyNewAccount() = runTest(dispatcher) {
        storage.favorite(ReadingOwner("local", "a"), "private-a", true)
        val vm = model(); vm.bind(member); settled(vm) { it.data.favorites.isNotEmpty() }
        member = "b"; vm.bind(member)
        assertTrue(vm.state.value.data.favorites.isEmpty()); assertEquals("b", vm.state.value.member)
        vm.favorite("a", "wrong-account", true); settled(vm) { it.member == "b" }
        assertTrue(storage.read(ReadingOwner("local", "b")).favorites.isEmpty())
        assertEquals(setOf("private-a"), storage.read(ReadingOwner("local", "a")).favorites)
    }
    @Test fun expiryRefreshHidesDataAndCorruptionCannotBeSilentlyOverwritten() = runTest(dispatcher) {
        val owner = ReadingOwner("local", member); storage.favorite(owner, "p", true)
        val vm = model(); vm.bind(member); settled(vm) { it.data.favorites.isNotEmpty() }
        valid = false; epoch.value++; settled(vm) { it.owner == null && it.error != null }
        assertTrue(vm.state.value.data.favorites.isEmpty()); assertFalse(vm.current(member))
        valid = true; storage.file(owner).writeBytes(byteArrayOf(1, 3, 5)); vm.retry()
        settled(vm) { it.owner == owner && it.error != null }
        vm.favorite(member, "q", true); settled(vm) { it.error != null }
        assertArrayEquals(byteArrayOf(1, 3, 5), storage.file(owner).readBytes())
    }
}
