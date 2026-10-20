package com.ensemble.scorelibrary.annotation

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

internal data class InkCheckpoint(val durable:Long=0,val failed:Long=0,val error:Throwable?=null)
internal data class InkReceipt(val session:InkRecovery.Session,val sequence:Long)
internal data class InkDraftEdit(val marks:List<InkMark>,val generation:Long,val before:List<InkMark>)

/** Keeps the newest completed edits outside the Activity lifetime, without a queue per stroke. */
@Singleton
class InkRecovery @Inject constructor(private val storage:InkDraftStore) {
    internal class Session internal constructor(
        val key:InkDraftKey,val source:RecoverySource,val recovered:InkDraftRecord?,
        internal var token:String?=recovered?.token,
    ) {
        internal var sequence=0L
        internal var encode:()->List<InkDraftWrite> = { recovered?.writes.orEmpty() }
        internal var retired=false
        internal var worker:Job?=null
        internal val status=MutableStateFlow(InkCheckpoint())
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val gate=Any()
    private val opening=Mutex()
    private val sessions=mutableMapOf<InkDraftKey,Session>()

    /** A new reader takes over only after all accepted edits from the previous reader reach disk. */
    internal suspend fun open(key:InkDraftKey,source:RecoverySource):Session=opening.withLock {
        require(key.space==source.space)
        require(source.sha256.matches(Regex("[a-f0-9]{64}"))) { "原谱哈希无效，未读取恢复笔迹" }
        val previous=synchronized(gate) {
            sessions[key]?.also { it.retired=true;start(it) }
        }
        previous?.let { await(InkReceipt(it,synchronized(gate) { it.sequence })) }
        val record=storage.read(key)?.let { storage.write(key,it.token,it.source,it.writes) }
        Session(key,source,record).also { synchronized(gate) { sessions[key]=it } }
    }

    /** Copies completed input immediately; encryption and file IO never run on the input thread. */
    internal fun submit(session:Session,writes:List<InkDraftWrite>):InkReceipt {
        val snapshot=writes.map { it.copy(marks=it.marks.toList()) }
        return enqueue(session,snapshot.isEmpty()) { snapshot }
    }
    /** Baseline hashing is expensive on large pages; compute it in the IO worker as well. */
    internal fun submitEdits(session:Session,edits:List<InkDraftEdit>):InkReceipt {
        val snapshot=edits.toList()
        return enqueue(session,snapshot.isEmpty()) { snapshot.map {
            InkDraftWrite(it.marks,it.generation,InkDraftStore.digest(InkCodec.encode(it.before)))
        } }
    }
    private fun enqueue(session:Session,empty:Boolean,encode:()->List<InkDraftWrite>):InkReceipt=synchronized(gate) {
        check(!session.retired && sessions[session.key]===session) { "恢复草稿已由另一阅读会话接管，请重新打开此页" }
        check(empty || session.recovered==null || session.recovered.source==session.source || session.token==null) {
            "原乐谱已变化，未覆盖旧谱的未保存笔迹"
        }
        check(session.sequence<Long.MAX_VALUE)
        session.sequence++
        session.encode=encode
        start(session)
        InkReceipt(session,session.sequence)
    }

    internal suspend fun await(receipt:InkReceipt) {
        val state=receipt.session.status.first { it.durable>=receipt.sequence || it.failed>=receipt.sequence }
        if(state.durable<receipt.sequence)throw checkNotNull(state.error)
    }

    /** Retirement rejects late callbacks but lets already accepted edits finish in the background. */
    internal fun release(session:Session)=synchronized(gate) {
        session.retired=true
        start(session)
    }

    /** Strong retirement differs from closing a page: old accepted edits must never be flushed back. */
    internal suspend fun retireVersions(space:String,member:String,versions:Set<String>,guard:()->Unit):Int=opening.withLock {
        // Store serializes all writers and persists the version barrier before touching draft files.
        // Even a worker that already copied its encode lambda cannot recreate a retired draft.
        val count=storage.retireVersions(space,member,versions,guard)
        val workers=synchronized(gate) {
            val affected=sessions.values.filter { it.key.space==space && it.key.scope.member==member && it.key.scope.version in versions }
            affected.mapNotNull { session ->
                session.retired=true
                session.encode={emptyList()}
                session.status.value=InkCheckpoint(session.status.value.durable,session.sequence,
                    IllegalStateException("此乐谱已彻底删除，旧笔迹已清除"))
                sessions.remove(session.key)
                session.worker?.also { it.cancel() }
            }
        }
        workers.forEach { it.join() }
        count
    }

    // All calls and mutations of Session metadata are guarded by gate.
    private fun start(session:Session) {
        if(session.worker!=null)return
        if(session.status.value.durable>=session.sequence) {
            if(session.retired && sessions[session.key]===session)sessions.remove(session.key)
            return
        }
        session.status.value=InkCheckpoint(durable=session.status.value.durable)
        session.worker=scope.launch(start=CoroutineStart.LAZY) {
            while(true) {
                val work=synchronized(gate) { Triple(session.sequence,session.encode,session.token) }
                try {
                    val writes=work.second()
                    val token=if(writes.isEmpty()) {
                        work.third?.let { storage.clear(session.key,it) };null
                    } else storage.write(session.key,work.third,session.source,writes).token
                    val finished=synchronized(gate) {
                        session.token=token
                        session.status.value=InkCheckpoint(durable=work.first)
                        if(session.sequence==work.first) {
                            session.worker=null
                            if(session.retired && sessions[session.key]===session)sessions.remove(session.key)
                            true
                        } else false
                    }
                    if(finished)return@launch
                } catch(e:CancellationException) { throw e }
                catch(e:Exception) {
                    synchronized(gate) {
                        session.status.value=InkCheckpoint(session.status.value.durable,session.sequence,e)
                        session.worker=null
                    }
                    return@launch
                }
            }
        }.also { it.start() }
    }

    internal fun closeForTest() { scope.cancel() }
}
