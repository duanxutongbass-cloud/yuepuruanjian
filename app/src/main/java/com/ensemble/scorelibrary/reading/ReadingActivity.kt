package com.ensemble.scorelibrary.reading

import com.ensemble.scorelibrary.domain.ResolvedConcertItem
import com.ensemble.scorelibrary.sync.TeamLibraryScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

internal class ReadingIdentity(val owner: ReadingOwner, val guard: () -> Unit)
internal data class ReadingResume(val page: Int? = null, val changed: Boolean = false)
internal data class ReadingIssue(val owner: ReadingOwner, val message: String)

/** One bounded, ordered writer independent of the reader/Activity lifetime. No grants are cached. */
@Singleton
class ReadingActivity internal constructor(private val storage: ReadingActivityStore,
    private val identify: (String) -> ReadingIdentity, internal val identityChanges: Flow<Unit>,
    private val worker: CoroutineScope) {
    @Inject constructor(storage: ReadingActivityStore, team: TeamLibraryScope) : this(storage, { member ->
        val collection = team.active.value
        if (collection == null) ReadingIdentity(ReadingOwner("local", member)) {
            check(team.active.value == null) { "曲库空间已切换" }
        } else {
            check(collection.member == member) { "团队账号已切换" }
            team.requireCurrent(collection)
            ReadingIdentity(ReadingOwner("team:${collection.key}", member)) { team.requireCurrent(collection) }
        }
    }, team.active.map { Unit }, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    internal class Session(val identity: ReadingIdentity, val entry: RecentReading) { internal var retired = false }
    private val gate = Any()
    private val pending = linkedMapOf<Triple<ReadingOwner, String, String>, Pair<Session, RecentReading>>()
    private var writing: Job? = null
    private val mutableChanges = MutableSharedFlow<ReadingOwner>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    internal val changes = mutableChanges.asSharedFlow()
    private val mutableIssue = MutableStateFlow<ReadingIssue?>(null)
    internal val issue = mutableIssue.asStateFlow()
    internal fun identity(member: String) = identify(member).also { it.guard() }
    internal fun session(member: String, score: ResolvedConcertItem) = Session(identity(member),
        RecentReading(score.pieceId, score.part.id, ReadingActivityStore.source(score), 0))
    internal suspend fun read(identity: ReadingIdentity) = storage.read(identity.owner, identity.guard)
    internal suspend fun resume(session: Session): ReadingResume {
        val last = read(session.identity).recent.firstOrNull { it.piece == session.entry.piece && it.part == session.entry.part }
            ?: return ReadingResume()
        return if (last.source == session.entry.source) ReadingResume(last.page) else ReadingResume(changed = true)
    }
    internal fun failed(identity: ReadingIdentity) {
        if (runCatching { identity.guard() }.isSuccess) mutableIssue.value = ReadingIssue(identity.owner,
            "本机最近阅读记录未能保存或读取，原记录已保留；可在曲库重试。")
    }
    internal fun retire(session: Session?) { synchronized(gate) { session?.retired = true } }
    internal fun record(session: Session, page: Int) {
        if (page !in 0..9999) return
        val job = synchronized(gate) {
            if (session.retired) return
            val key = Triple(session.identity.owner, session.entry.piece, session.entry.part)
            pending.remove(key)
            pending[key] = session to session.entry.copy(page = page)
            // Matches the natural retention of the persisted history; favorites never enter this queue.
            while (pending.size > ReadingActivityStore.MAX_RECENT) pending.remove(pending.keys.first())
            writing ?: worker.launch(start = CoroutineStart.LAZY) {
                while (true) {
                    val next = synchronized(gate) {
                        val first = pending.entries.firstOrNull()
                        if (first == null) { writing = null; null } else pending.remove(first.key)
                    } ?: break
                    val (target, entry) = next
                    try {
                        storage.record(target.identity.owner, entry, target.identity.guard)
                        if (mutableIssue.value?.owner == target.identity.owner) mutableIssue.value = null
                        mutableChanges.tryEmit(target.identity.owner)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failed(target.identity) }
                }
            }.also { writing = it }
        }
        job.start()
    }
    internal suspend fun awaitIdle() { while (true) { (synchronized(gate) { writing } ?: return).join() } }
    internal suspend fun favorite(identity: ReadingIdentity, piece: String, value: Boolean): ReadingActivityData {
        val result = storage.favorite(identity.owner, piece, value, identity.guard)
        mutableChanges.tryEmit(identity.owner)
        return result
    }
    internal suspend fun clearRecent(identity: ReadingIdentity): ReadingActivityData {
        // A fresh page accepted after this boundary may create a new recent record, as expected.
        awaitIdle()
        val result = storage.clearRecent(identity.owner, identity.guard)
        mutableChanges.tryEmit(identity.owner)
        return result
    }
    internal suspend fun clearFavorites(identity: ReadingIdentity): ReadingActivityData {
        val result = storage.clearFavorites(identity.owner, identity.guard)
        mutableChanges.tryEmit(identity.owner)
        return result
    }
}
