package com.ensemble.scorelibrary.reading

import android.content.Context
import com.ensemble.scorelibrary.account.AccountVault
import com.ensemble.scorelibrary.account.teamSecretKey
import com.ensemble.scorelibrary.backup.ArchiveFormat
import com.ensemble.scorelibrary.domain.ResolvedConcertItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

data class ReadingOwner(val space: String, val member: String)
data class RecentReading(val piece: String, val part: String, val source: String, val page: Int)
data class ReadingActivityData(val favorites: Set<String> = emptySet(), val recent: List<RecentReading> = emptyList())

/** IDs and positions only. Titles, PDFs, grants and credentials never enter this private file. */
@Singleton
class ReadingActivityStore internal constructor(private val root: File, private val secret: () -> SecretKey) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        File(context.noBackupFilesDir, "reading-activity-v1"), { teamSecretKey("reading-activity-v1") })
    companion object {
        internal const val MAX_RECENT = 100
        internal const val MAX_FAVORITES = 2000
        internal const val MAXIMUM = 1024 * 1024
        private val diskLock = Any()
        internal fun hash(value: String) = ArchiveFormat.digest(value.toByteArray(Charsets.UTF_8))
        /** A changed version, hash, source range, binding or part cannot inherit an old page. */
        fun source(item: ResolvedConcertItem): String = hash(JSONArray().put(item.pieceId).put(item.part.id)
            .put(item.part.definitionId).put(JSONArray().apply { item.segments.forEach { s ->
                put(JSONArray().put(s.bindingId).put(s.movementId ?: JSONObject.NULL).put(s.scorePdfId)
                    .put(s.versionId).put(s.fileHash).put(s.startPage).put(s.endPage))
            } }).toString())
    }
    private fun id(value: String) { require(value.isNotBlank() && value.length <= 200 && value.none { it.isISOControl() }) }
    private fun owner(owner: ReadingOwner) { id(owner.space); id(owner.member) }
    internal fun file(owner: ReadingOwner): File {
        owner(owner)
        check(root.isDirectory || root.mkdirs()) { "无法创建本机阅读记录目录" }
        return File(root, hash(JSONArray().put(owner.space).put(owner.member).toString()) + ".enc")
    }
    private fun vault(owner: ReadingOwner) = AccountVault(file(owner), MAXIMUM, secret)
    private fun retired(owner: ReadingOwner): Pair<Set<String>, Set<String>> {
        val raw = retirement(owner).read() ?: return emptySet<String>() to emptySet()
        ArchiveFormat.depthCheck(raw)
        val row = JSONObject(raw)
        exact(row, setOf("format", "space", "member", "pieces", "parts"))
        require(row.get("format") == 1 && string(row, "space") == owner.space && string(row, "member") == owner.member)
        fun ids(key: String): Set<String> {
            val values = row.getJSONArray(key); require(values.length() <= 500_000)
            return (0 until values.length()).map { (values.get(it) as String).also(::id) }
                .also { require(it.distinct().size == it.size) }.toSet()
        }
        return ids("pieces") to ids("parts")
    }
    private fun retirement(owner: ReadingOwner) = AccountVault(File(file(owner).parentFile,
        file(owner).nameWithoutExtension + ".retired"), 32 * 1024 * 1024, secret)
    private fun prune(data: ReadingActivityData, gone: Pair<Set<String>, Set<String>>) = data.copy(
        favorites = data.favorites - gone.first,
        recent = data.recent.filterNot { it.piece in gone.first || it.part in gone.second })
    /** Persist the barrier before rewriting history. Old page callbacks cannot recreate it after restart. */
    internal suspend fun retire(owner: ReadingOwner, pieces: Set<String>, parts: Set<String>, guard: () -> Unit) =
        withContext(Dispatchers.IO) { synchronized(diskLock) {
            owner(owner); (pieces + parts).forEach(::id); guard()
            val old = retired(owner)
            val gone = (old.first + pieces) to (old.second + parts)
            require(gone.first.size <= 500_000 && gone.second.size <= 500_000)
            retirement(owner).write(JSONObject().put("format", 1).put("space", owner.space).put("member", owner.member)
                .put("pieces", JSONArray(gone.first.sorted())).put("parts", JSONArray(gone.second.sorted())).toString())
            val before = readNow(owner)
            val after = prune(before, gone)
            guard()
            if (after != before) vault(owner).write(encode(owner, after))
        } }
    private fun string(row: JSONObject, key: String): String = (row.get(key) as? String ?: error("阅读记录格式不正确")).also(::id)
    private fun exact(row: JSONObject, keys: Set<String>) { require(ArchiveFormat.keys(row) == keys) }
    private fun encode(owner: ReadingOwner, data: ReadingActivityData): String {
        owner(owner)
        require(data.favorites.size <= MAX_FAVORITES && data.recent.size <= MAX_RECENT)
        data.favorites.forEach(::id)
        require(data.recent.map { it.piece to it.part }.distinct().size == data.recent.size)
        return JSONObject().put("format", 1).put("space", owner.space).put("member", owner.member)
            .put("favorites", JSONArray(data.favorites.sorted())).put("recent", JSONArray().apply {
                data.recent.forEach { r ->
                    id(r.piece); id(r.part)
                    require(r.source.matches(Regex("[a-f0-9]{64}")) && r.page in 0..9999)
                    put(JSONObject().put("piece", r.piece).put("part", r.part).put("source", r.source).put("page", r.page))
                }
            }).toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAXIMUM) }
    }
    private fun readNow(owner: ReadingOwner): ReadingActivityData {
        val value = vault(owner).read() ?: return ReadingActivityData()
        ArchiveFormat.depthCheck(value)
        val row = JSONObject(value)
        exact(row, setOf("format", "space", "member", "favorites", "recent"))
        require(row.get("format") is Int && row.getInt("format") == 1)
        check(string(row, "space") == owner.space && string(row, "member") == owner.member) { "阅读记录身份不匹配，原文件已保留" }
        val favorites = row.getJSONArray("favorites")
        val recent = row.getJSONArray("recent")
        require(favorites.length() <= MAX_FAVORITES && recent.length() <= MAX_RECENT)
        val ids = (0 until favorites.length()).map { (favorites.get(it) as? String ?: error("收藏格式不正确")).also(::id) }
        require(ids.distinct().size == ids.size)
        val data = ReadingActivityData(ids.toSet(), (0 until recent.length()).map { i ->
            val r = recent.getJSONObject(i)
            exact(r, setOf("piece", "part", "source", "page"))
            val page = r.get("page")
            require(page is Int && page in 0..9999)
            RecentReading(string(r, "piece"), string(r, "part"), string(r, "source"), page)
        })
        encode(owner, data)
        return data
    }
    internal suspend fun read(owner: ReadingOwner, guard: () -> Unit = {}): ReadingActivityData = withContext(Dispatchers.IO) {
        synchronized(diskLock) { guard(); prune(readNow(owner), retired(owner)).also { guard() } }
    }
    private suspend fun edit(owner: ReadingOwner, guard: () -> Unit, change: (ReadingActivityData) -> ReadingActivityData) =
        withContext(Dispatchers.IO) { synchronized(diskLock) {
            guard()
            val before = readNow(owner)
            val gone = retired(owner)
            val after = prune(change(prune(before, gone)), gone)
            val value = encode(owner, after)
            guard()
            if (after != before) vault(owner).write(value)
            after
        } }
    internal suspend fun record(owner: ReadingOwner, entry: RecentReading, guard: () -> Unit = {}) = edit(owner, guard) { before ->
        before.copy(recent = (listOf(entry) + before.recent.filterNot { it.piece == entry.piece && it.part == entry.part }).take(MAX_RECENT))
    }
    internal suspend fun favorite(owner: ReadingOwner, piece: String, value: Boolean, guard: () -> Unit = {}) = edit(owner, guard) { before ->
        id(piece)
        before.copy(favorites = if (value) before.favorites + piece else before.favorites - piece)
    }
    internal suspend fun clearRecent(owner: ReadingOwner, guard: () -> Unit = {}) = edit(owner, guard) { it.copy(recent = emptyList()) }
    internal suspend fun clearFavorites(owner: ReadingOwner, guard: () -> Unit = {}) = edit(owner, guard) { it.copy(favorites = emptySet()) }
}
