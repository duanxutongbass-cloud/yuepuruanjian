package com.ensemble.scorelibrary.diagnostics

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.ensemble.scorelibrary.BuildConfig
import com.ensemble.scorelibrary.account.*
import com.ensemble.scorelibrary.sync.TeamLibraryScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class TestEvent { APP_START, APP_BACKGROUND, STAMP_OPEN, STAMP_CLOSE, STAMP_CHOOSE, STAMP_SIZE,
    STAMP_RESIZE, PDF_RENDER, UPLOAD_SELECT, UPLOAD_READ, UPLOAD_PREFLIGHT, UPLOAD_SEND, UPLOAD_PUBLISH, UPLOAD_CANCEL }
enum class TestResult { START, OK, ERROR, CANCELLED, BLOCKED }
enum class TestCode { NONE, NETWORK, IO, METADATA_INVALID, SESSION_INVALID, FORBIDDEN, NUMBER_CONFLICT,
    PART_VERSION_CONFLICT, REQUEST_CONFLICT, SERVICE_UNAVAILABLE, OFFLINE, FILE_INVALID, STORAGE_UNKNOWN, UNKNOWN }
private fun Enum<*>.wire() = name.lowercase(java.util.Locale.ROOT)
private fun identifier(value: String) { require(UUID.fromString(value).toString() == value) }

internal data class OperationRecord(val id: String = UUID.randomUUID().toString(), val operation: String,
    val at: Long = System.currentTimeMillis(), val version: Int = BuildConfig.VERSION_CODE,
    val event: TestEvent, val result: TestResult = TestResult.OK, val code: TestCode = TestCode.NONE,
    val count: Int = 0, val elapsed: Long = 0, val width: Int = 0, val height: Int = 0) {
    init {
        identifier(id); identifier(operation)
        require(at in 0..4102444800000L && version in 0..1000000 && count in 0..10000 &&
            elapsed in 0..86400000 && width in 0..10000 && height in 0..10000)
    }
    fun json() = JSONObject().put("id",id).put("operation",operation).put("at",at).put("version",version)
        .put("event",event.wire()).put("result",result.wire()).put("code",code.wire())
        .put("count",count).put("elapsed",elapsed).put("width",width).put("height",height)
    companion object {
        fun read(j:JSONObject) = OperationRecord(j.getString("id"),j.getString("operation"),j.getLong("at"),j.getInt("version"),
            TestEvent.valueOf(j.getString("event").uppercase(java.util.Locale.ROOT)),
            TestResult.valueOf(j.getString("result").uppercase(java.util.Locale.ROOT)),
            TestCode.valueOf(j.getString("code").uppercase(java.util.Locale.ROOT)),
            j.getInt("count"),j.getLong("elapsed"),j.getInt("width"),j.getInt("height"))
    }
}

/** Bounded, encrypted, account/device-bound, and idempotent after a lost server acknowledgement. */
internal class OperationQueue(private val vault: AccountVault, private val binding: String) {
    private fun read(): List<OperationRecord> {
        val json = vault.read()?.let(::JSONObject) ?: return emptyList()
        require(json.getString("binding") == binding)
        val rows=json.getJSONArray("events"); require(rows.length()<=500)
        return (0 until rows.length()).map { OperationRecord.read(rows.getJSONObject(it)) }
    }
    private fun write(rows:List<OperationRecord>) {
        vault.write(JSONObject().put("binding",binding).put("events",JSONArray(rows.map { it.json() })).toString())
    }
    fun add(record:OperationRecord) = addAll(listOf(record))
    fun addAll(records:List<OperationRecord>) { write((read()+records).takeLast(500)) }
    fun batch():List<OperationRecord> = read().take(20)
    fun size() = read().size
    fun acknowledge(ids:List<String>, submitted:List<OperationRecord>) {
        require(ids == submitted.map { it.id }) { "操作记录回执不匹配" }
        write(read().filterNot { it.id in ids })
    }
}

data class OperationStatus(val key:String?=null, val pending:Int=0, val sentAt:Long=0, val failed:Boolean=false)
val LocalTestOperations = staticCompositionLocalOf<TestOperations?> { null }

@Singleton
class TestOperations @Inject constructor(@param:ApplicationContext context:Context,
    private val teams:TeamLibraryScope, private val gateway:TeamAccountGateway) {
    private val root=File(context.noBackupFilesDir,"test-operations")
    private data class Pending(val key:String,val record:OperationRecord)
    private val channel=Channel<Pending>(200)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutable=MutableStateFlow(OperationStatus())
    val status=mutable.asStateFlow()
    private var retryAfter=0L
    private var failures=0
    init {
        if (BuildConfig.TEST_OPERATION_LOGS) scope.launch {
            teams.active.collect { if (it != null) record(TestEvent.APP_START) }
        }
        if (BuildConfig.TEST_OPERATION_LOGS) scope.launch {
            while(isActive) {
                try {
                    val pending=withTimeoutOrNull(2000) { channel.receive() }
                    if(pending!=null) {
                        val ready=mutableListOf(pending)
                        repeat(19) { channel.tryReceive().getOrNull()?.let(ready::add) }
                        ready.groupBy { it.key }.forEach { (key,rows) -> queue(key).addAll(rows.map { it.record }) }
                    }
                    flush()
                } catch(cancelled:CancellationException) { throw cancelled }
                catch(_:Exception) { mutable.value=mutable.value.copy(failed=true) }
            }
        }
    }
    private fun queue(key:String):OperationQueue {
        identifier(key); identifier(gateway.device)
        check(root.isDirectory||root.mkdirs())
        return OperationQueue(AccountVault(File(root,"$key-${gateway.device}.bin"),512*1024) {
            teamSecretKey("scorelibrary-test-operations-v1")
        }, "$key/${gateway.device}")
    }
    fun record(event:TestEvent, result:TestResult=TestResult.OK, operation:String=UUID.randomUUID().toString(),
        code:TestCode=TestCode.NONE,count:Int=0,elapsed:Long=0,width:Int=0,height:Int=0):String {
        if(!BuildConfig.TEST_OPERATION_LOGS) return operation
        val owner=teams.active.value ?: return operation
        // No free-form messages, URLs, filenames, credentials or ink can enter this API.
        val record=OperationRecord(operation=operation,event=event,result=result,code=code,
            count=count.coerceIn(0,10000),elapsed=elapsed.coerceIn(0,86400000),width=width.coerceIn(0,10000),height=height.coerceIn(0,10000))
        if(!channel.trySend(Pending(owner.key,record)).isSuccess) mutable.value=mutable.value.copy(failed=true)
        return operation
    }
    private fun flush() {
        val owner=teams.active.value ?: return
        val session=owner.session ?: return
        val queue=queue(owner.key)
        mutable.value=mutable.value.takeIf { it.key==owner.key }?.copy(pending=queue.size())
            ?: OperationStatus(key=owner.key,pending=queue.size())
        if(session.offline || android.os.SystemClock.elapsedRealtime()<retryAfter) return
        val batch=queue.batch(); if(batch.isEmpty()) return
        try {
            teams.requireCurrent(owner)
            val reply=gateway.request(session,"/v1/test-operations",JSONObject().put("events",JSONArray(batch.map { it.json() })))
            val accepted=reply.getJSONArray("accepted")
            val ids=(0 until accepted.length()).map { accepted.getString(it) }
            queue.acknowledge(ids,batch)
            failures=0; retryAfter=0
            mutable.value=OperationStatus(owner.key,queue.size(),reply.getLong("receivedAt"),false)
        } catch(_:Exception) {
            failures=(failures+1).coerceAtMost(5)
            retryAfter=android.os.SystemClock.elapsedRealtime()+(2000L shl failures)
            mutable.value=mutable.value.copy(failed=true)
        }
    }
    companion object {
        fun error(error:Exception):TestCode=when(error) {
            is OfflineUnavailable -> TestCode.OFFLINE
            is AccountRejected -> when(error.code) {
                "session_invalid","device_revoked" -> TestCode.SESSION_INVALID
                "forbidden" -> TestCode.FORBIDDEN
                "number_conflict" -> TestCode.NUMBER_CONFLICT
                "part_version_conflict" -> TestCode.PART_VERSION_CONFLICT
                "request_conflict" -> TestCode.REQUEST_CONFLICT
                "service_unavailable" -> TestCode.SERVICE_UNAVAILABLE
                else -> TestCode.UNKNOWN
            }
            is IllegalArgumentException -> TestCode.METADATA_INVALID
            is IOException -> TestCode.NETWORK
            is IllegalStateException -> TestCode.FILE_INVALID
            else -> TestCode.UNKNOWN
        }
    }
}
