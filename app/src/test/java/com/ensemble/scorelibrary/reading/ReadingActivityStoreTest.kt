package com.ensemble.scorelibrary.reading

import com.ensemble.scorelibrary.account.AccountVault
import com.ensemble.scorelibrary.core.model.CacheStatus
import com.ensemble.scorelibrary.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
class ReadingActivityStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
    private val owner = ReadingOwner("team-one", "member-a")
    private val source = "a".repeat(64)
    private fun store() = ReadingActivityStore(temporary.root) { key }
    private fun entry(piece: String = "p", part: String = "part", page: Int = 0) = RecentReading(piece, part, source, page)

    @Test fun retirement() = runTest {
        store().record(owner, entry()); store().favorite(owner, "p", true)
        store().record(owner, entry("keep", "keep-part")); store().favorite(owner, "keep", true)
        val other = owner.copy(space = "local")
        store().record(other, entry()); store().favorite(other, "p", true)
        store().retire(owner, setOf("p"), setOf("part")) {}
        store().record(owner, entry(page = 9)); store().favorite(owner, "p", true)
        assertEquals(setOf("keep"), store().read(owner).favorites)
        assertEquals(listOf(entry("keep", "keep-part")), store().read(owner).recent)
        assertEquals(setOf("p"), store().read(other).favorites)
        val raw = AccountVault(store().file(owner), ReadingActivityStore.MAXIMUM) { key }.read()!!
        assertFalse(org.json.JSONObject(raw).getJSONArray("favorites").toString().contains("\"p\""))
    }
    @Test fun partRetirement() = runTest {
        store().favorite(owner, "p", true); store().record(owner, entry())
        store().record(owner, entry(part = "keep"))
        store().retire(owner, emptySet(), setOf("part")) {}
        store().record(owner, entry())
        assertEquals(setOf("p"), store().read(owner).favorites)
        assertEquals(listOf(entry(part = "keep")), store().read(owner).recent)
    }
    @Test fun retirementFailure() = runTest {
        store().record(owner, entry())
        val original = store().file(owner).readBytes()
        try { store().retire(owner, setOf("p"), emptySet()) { error("wrong identity") }; fail() } catch (_: IllegalStateException) {}
        assertArrayEquals(original, store().file(owner).readBytes())
        val broken = original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        store().file(owner).writeBytes(broken)
        try { store().retire(owner, setOf("p"), emptySet()) {}; fail() } catch (_: Exception) {}
        assertArrayEquals(broken, store().file(owner).readBytes())
        // A failed cleanup is not acknowledgement. Its durable barrier still blocks late writes.
        store().file(owner).writeBytes(original)
        store().record(owner, entry())
        assertTrue(store().read(owner).recent.isEmpty())
    }

    @Test fun encryptedPositionsAndFavoritesSurviveNewStoreAndClearHistoryKeepsFavorites() = runTest {
        val store = store()
        store.record(owner, entry(page = 7)); store.favorite(owner, "p", true)
        val cipher = store.file(owner).readBytes().toString(Charsets.UTF_8)
        assertFalse(cipher.contains("favorites")); assertFalse(cipher.contains("member-a"))
        assertEquals(7, store().read(owner).recent.single().page)
        assertEquals(setOf("p"), store().read(owner).favorites)
        store().clearRecent(owner)
        assertTrue(store.read(owner).recent.isEmpty()); assertEquals(setOf("p"), store.read(owner).favorites)
        store.record(owner, entry(page = 2)); store.clearFavorites(owner)
        assertTrue(store.read(owner).favorites.isEmpty()); assertEquals(entry(page = 2), store.read(owner).recent.single())
    }
    @Test fun accountAndLocalTeamSpacesAreIsolatedEvenWithSameMember() = runTest {
        val store = store(); store.record(owner, entry()); store.favorite(owner, "p", true)
        assertEquals(ReadingActivityData(), store.read(owner.copy(member = "member-b")))
        assertEquals(ReadingActivityData(), store.read(owner.copy(space = "local")))
        assertEquals(ReadingActivityData(), store.read(owner.copy(space = "team-two")))
    }
    @Test fun recencyIsDeterministicAcrossClockChangesAndBoundedByPart() = runTest {
        val store = store()
        repeat(103) { store.record(owner, entry("p$it")) }
        store.record(owner, entry("p10", page = 9)); store.record(owner, entry("p10", "other-part", 1))
        val recent = store().read(owner).recent
        assertEquals(100, recent.size)
        assertEquals(entry("p10", "other-part", 1), recent.first())
        assertEquals(entry("p10", page = 9), recent[1])
        assertFalse(recent.any { it.piece == "p0" })
        assertEquals(1, recent.count { it.piece == "p10" && it.part == "part" })
    }
    @Test fun concurrentStoresMergeCurrentDiskStateRatherThanOverwriteCachedFavorites() = runTest {
        coroutineScope { (0..24).map { i -> async { store().favorite(owner, "p$i", true) } }.awaitAll() }
        assertEquals(25, store().read(owner).favorites.size)
        store().record(owner, entry()); store().favorite(owner, "p1", false)
        assertEquals(24, store().read(owner).favorites.size); assertEquals(entry(), store().read(owner).recent.single())
    }
    @Test fun deniedOrChangedIdentityCannotReadOrWriteAndDoesNotDeleteOldRecord() = runTest {
        val store = store(); store.record(owner, entry())
        val original = store.file(owner).readBytes()
        try { store.favorite(owner, "new", true) { error("expired") }; fail() } catch (_: IllegalStateException) { }
        try { store.read(owner) { error("changed") }; fail() } catch (_: IllegalStateException) { }
        var checks = 0
        try { store.record(owner, entry(page = 4)) { if (++checks == 2) error("changed") }; fail() } catch (_: IllegalStateException) { }
        assertArrayEquals(original, store.file(owner).readBytes())
    }
    @Test fun corruptionIsPreservedAndCannotBeSilentlyReplacedByNewHistory() = runTest {
        val store = store(); store.record(owner, entry())
        val broken = store.file(owner).readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        store.file(owner).writeBytes(broken)
        try { store.record(owner, entry(page = 3)); fail() } catch (_: Exception) { }
        assertArrayEquals(broken, store.file(owner).readBytes())
    }
    @Test fun copiedCiphertextCannotMasqueradeAsAnotherAccount() = runTest {
        val store = store(); store.record(owner, entry())
        val other = owner.copy(member = "member-b")
        store.file(other).writeBytes(store.file(owner).readBytes())
        try { store.favorite(other, "p", true); fail() } catch (_: IllegalStateException) { }
        assertEquals(entry(), store.read(owner).recent.single())
    }
    @Test fun malformedPagesDuplicateRecordsAndFutureFormatsAreRejectedWithoutOverwrite() = runTest {
        val store = store(); store.record(owner, entry())
        val vault = AccountVault(store.file(owner), ReadingActivityStore.MAXIMUM) { key }
        val valid = vault.read()!!
        listOf(valid.replace("\"page\":0", "\"page\":0.5"), valid.replace("\"format\":1", "\"format\":2"),
            valid.replace("\"favorites\":[]", "\"favorites\":[\"p\",\"p\"]")).forEach { bad ->
            vault.write(bad); val bytes = store.file(owner).readBytes()
            try { store.favorite(owner, "another", true); fail() } catch (_: Exception) { }
            assertArrayEquals(bytes, store.file(owner).readBytes())
        }
        vault.write(valid)
        try { store.record(owner, entry(page = -1)); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(valid, vault.read())
    }
    @Test fun favoriteCapacityFailsWithoutDroppingExistingFavorites() = runTest {
        val store = store(); val vault = AccountVault(store.file(owner), ReadingActivityStore.MAXIMUM) { key }
        vault.write(org.json.JSONObject().put("format", 1).put("space", owner.space).put("member", owner.member)
            .put("favorites", org.json.JSONArray((0 until 2000).map { "p$it" })).put("recent", org.json.JSONArray()).toString())
        val bytes = store.file(owner).readBytes()
        try { store.favorite(owner, "extra", true); fail() } catch (_: IllegalArgumentException) { }
        assertArrayEquals(bytes, store.file(owner).readBytes())
        store.favorite(owner, "p0", false); assertEquals(1999, store.read(owner).favorites.size)
    }
    @Test fun sourceFingerprintFollowsAuthorizationBindingRatherThanTitlePathOrCache() {
        val segment = ResolvedScoreSegment("binding", null, "pdf", "v1", "path", source, 2, 5, CacheStatus.NOT_CACHED)
        val item = ResolvedConcertItem("item", "p", "name", VisiblePart("part", "definition", "name"), listOf(segment))
        val fingerprint = ReadingActivityStore.source(item)
        assertEquals(fingerprint, ReadingActivityStore.source(item.copy(title = "renamed", itemId = "concert-item",
            segments = listOf(segment.copy(managedFilePath = "moved", cachedFilePath = "cached")))))
        listOf(segment.copy(bindingId = "other"), segment.copy(versionId = "v2"), segment.copy(fileHash = "b".repeat(64)),
            segment.copy(startPage = 3), segment.copy(endPage = 4)).forEach {
            assertNotEquals(fingerprint, ReadingActivityStore.source(item.copy(segments = listOf(it))))
        }
        assertNotEquals(fingerprint, ReadingActivityStore.source(item.copy(part = item.part.copy(id = "new"))))
    }
}
