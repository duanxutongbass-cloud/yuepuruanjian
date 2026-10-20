package com.ensemble.scorelibrary.sync

import com.ensemble.scorelibrary.domain.ScoreNumber

import com.ensemble.scorelibrary.account.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject

open class TeamSyncFailure(message: String, cause: Throwable? = null) : IOException(message, cause)
class TeamDownloadCorrupt(message: String) : TeamSyncFailure(message)

data class TeamArtifact(val id: String, val title: String, val partName: String, val hash: String,
    val size: Long, val pages: Int, val number: String, val category: String,val pieceGeneration:String?=null) {
    val pieceKey by lazy { (if (number.isEmpty()) "artifact:$id" else "sequence:${ScoreNumber.parse(number).sequence}") +
        (pieceGeneration?.let { ":released:$it" } ?: "") }
    val definitionKey by lazy { if (number.isEmpty()) "name:$partName" else "instrument:${number.take(2)}" }
    val piece by lazy { teamId("piece/$pieceKey") }
    val definition by lazy { teamId("definition/$definitionKey") }
    val part by lazy { teamId("part/$pieceKey/$definitionKey") }
    val pdf by lazy { teamId("pdf/$id") }
    val version by lazy { teamId("version/$id") }
}
data class TeamProgram(val id: String, val title: String, val pieceKeys: List<String>,
    val freezeId: String? = null, val frozenArtifacts: List<String> = emptyList()) {
    // A changed authorization projection is a new local scene, never a rewrite of an old snapshot.
    val concert get() = teamId("concert/$id" + if(freezeId==null) "" else "/$freezeId/${frozenArtifacts.sorted().joinToString(",")}")
    fun item(key:String) = teamId("item/${if(freezeId==null) id else concert}/$key")
}
data class TeamCatalog(val revision: Long, val artifacts: List<TeamArtifact>, val programs: List<TeamProgram> = emptyList()) {
    companion object {
        fun parse(json: JSONObject, session: TeamSession): TeamCatalog {
            val principal = TeamPrincipal.parse(json.getJSONObject("principal"))
            require(principal.joined && session.principal.joined) { "未通过入团审核" }
            require(principal.accountId == session.principal.accountId && principal.teamId == session.principal.teamId &&
                principal.memberId == session.principal.memberId) { "团队身份不匹配" }
            require(principal.numberingProtocol == session.principal.numberingProtocol) { "编号协议已变化，请重新核验登录" }
            if (principal.numberingProtocol == 2) require(json.get("protocolVersion") == 2) { "编号协议声明不匹配" }
            val revision = json.getLong("revision").also { require(it >= 0) }
            val rows = json.getJSONArray("artifacts").also { require(it.length() <= 5000) }
            val artifacts = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                fun text(key: String, optional: Boolean = false) = row.optString(key).also {
                    require(it.length <= 200 && (optional || it.isNotBlank()) && it.none { c -> c.isISOControl() })
                }
                val id = row.getString("artifactId").also { require(UUID.fromString(it).toString() == it) }
                val hash = row.getString("hash").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
                val number = text("scoreNumber", true).also { require(it.isEmpty() || ScoreNumber.parse(it).let { n -> session.principal.numberingProtocol == 2 || n.namingVersion == 1 }) }
                if (row.has("scoreNumber")) require(row.get("scoreNumber") is String)
                if (principal.numberingProtocol == 2) {
                    val parsed=ScoreNumber.optional(number)
                    require(row.get("sequence") == (parsed?.sequence ?: JSONObject.NULL) &&
                        row.get("namingVersion") == (parsed?.namingVersion ?: JSONObject.NULL)) { "完整编号元数据不一致" }
                }
                require(row.getBoolean("independentSource")) { "只接收独立声部分谱" }
                val generation=if(row.has("pieceGeneration")) (row.get("pieceGeneration").also { require(it is String) } as String).also {
                    require(number.isNotEmpty() && UUID.fromString(it).toString()==it)
                } else null
                TeamArtifact(id, text("title"), text("partName"), hash,
                    row.getLong("size").also { require(it in 1..100L * 1024 * 1024) },
                    row.getInt("pageCount").also { require(it in 1..10000) }, number, text("category", true),generation)
            }
            require(artifacts.map { it.id }.distinct().size == artifacts.size)
            artifacts.filter { it.number.isNotEmpty() }.groupBy { ScoreNumber.parse(it.number).sequence }.values.forEach {
                require(it.map { a -> a.pieceGeneration }.distinct().size==1) { "同一编号存在不同曲目代次，请重新加载" }
            }
            if (artifacts.map { it.part }.distinct().size != artifacts.size)
                throw TeamSyncFailure("同一编号声部存在多份已发布乐谱，请管理员确认版本后重新发布")
            artifacts.groupBy { it.piece }.values.forEach { group ->
                if (group.map { it.title to it.category }.distinct().size != 1)
                    throw TeamSyncFailure("同一曲目编号的名称或分类不一致，请管理员核对")
            }
            require(artifacts.sumOf { it.size } <= 2L * 1024 * 1024 * 1024) { "本次授权文件超过 2 GB，请分批处理" }
            val programs = json.optJSONArray("programs")?.let { list ->
                require(list.length() <= 1000)
                (0 until list.length()).map { i ->
                    val row = list.getJSONObject(i)
                    val id = row.getString("id").also { require(UUID.fromString(it).toString() == it) }
                    val title = row.getString("title").also { require(it.isNotBlank() && it.length <= 100 && it.none(Char::isISOControl)) }
                    val keys = row.getJSONArray("pieceKeys").also { require(it.length() in 1..200) }
                    val pieces = (0 until keys.length()).map { keys.getString(it) }
                    require(pieces.distinct().size == pieces.size && pieces.all { key -> artifacts.any { it.pieceKey == key } })
                    val freeze = if(row.has("freezeId") && !row.isNull("freezeId")) row.getString("freezeId").also {
                        require(UUID.fromString(it).toString()==it)
                    } else null
                    val frozen = if(freeze!=null) {
                        val ids=row.getJSONArray("frozenArtifactIds");require(ids.length() in 1..5000)
                        (0 until ids.length()).map { ids.getString(it) }.also { list ->
                            require(list.distinct().size==list.size && list.all { key -> artifacts.any { it.id==key } })
                            require(artifacts.filter { it.id in list }.map { it.pieceKey }.toSet()==pieces.toSet())
                        }
                    } else { require(!row.has("frozenArtifactIds"));emptyList() }
                    TeamProgram(id, title, pieces, freeze, frozen)
                }.also { require(it.map { p -> p.id }.distinct().size == it.size) }
            }.orEmpty()
            return TeamCatalog(revision, artifacts, programs)
        }
    }
}

interface TeamScoreTransport {
    suspend fun catalog(session: TeamSession): TeamCatalog
    suspend fun download(session: TeamSession, artifact: TeamArtifact, target: File)
    suspend fun transfer(session:TeamSession,artifact:TeamArtifact,target:File,progress:(Long,Long)->Unit) {
        download(session,artifact,target);progress(artifact.size,artifact.size)
    }
    suspend fun frozen(session:TeamSession,program:TeamProgram,catalog:TeamCatalog):TeamProgramSnapshot =
        throw TeamSyncFailure("冻结节目单尚未下载，请联网同步")
}

class TeamScoreClient @Inject constructor(private val gateway: TeamAccountGateway) : TeamScoreTransport {
    override suspend fun frozen(session:TeamSession,program:TeamProgram,catalog:TeamCatalog) = withContext(Dispatchers.IO) {
        if(session.offline) throw OfflineUnavailable("请联网下载本场冻结版本")
        check(program.freezeId!=null)
        TeamProgramSnapshot.parse(gateway.scoreRequest(session,"/v1/program-freezes/${program.id}/${program.freezeId}"),program,catalog)
    }
    override suspend fun catalog(session: TeamSession) = withContext(Dispatchers.IO) {
        TeamCatalog.parse(gateway.scoreRequest(session, "/v1/catalog"), session)
    }
    override suspend fun download(session: TeamSession, artifact: TeamArtifact, target: File) = receive(session,artifact,target,false) { _,_-> }
    override suspend fun transfer(session:TeamSession,artifact:TeamArtifact,target:File,progress:(Long,Long)->Unit) =
        receive(session,artifact,target,true,progress)
    private suspend fun receive(session:TeamSession,artifact:TeamArtifact,target:File,resume:Boolean,progress:(Long,Long)->Unit):Unit = withContext(Dispatchers.IO) {
        if (session.offline) throw OfflineUnavailable("请联网核验后下载乐谱")
        var offset=if(resume && target.isFile) target.length() else 0L
        if(offset !in 0..artifact.size) throw TeamDownloadCorrupt("未完成文件大小异常，请重新下载")
        var digest=MessageDigest.getInstance("SHA-256")
        progress(offset,artifact.size)
        if(offset>0) target.inputStream().use { input ->
            val buffer=ByteArray(64*1024)
            while(true) { ensureActive();val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count) }
        }
        fun checksum()=digest.digest().joinToString("") { "%02x".format(it) }
        if(resume && offset==artifact.size) {
            if(checksum()!=artifact.hash) throw TeamDownloadCorrupt("暂存乐谱校验失败，请重新下载")
            return@withContext
        }
        val connection = URL(accountServer(session.server) + session.scorePath("/v1/artifacts/") + artifact.id).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Authorization", "Bearer ${session.token}")
            connection.setRequestProperty("X-Score-Team",session.principal.teamId)
            connection.setRequestProperty("X-Device-Id", gateway.device)
            connection.setRequestProperty("Accept", "application/pdf")
            connection.setRequestProperty("If-Match", "\"${artifact.hash}\"")
            if(offset>0) connection.setRequestProperty("Range","bytes=$offset-")
            val status = connection.responseCode
            if (status != 200 && !(offset>0 && status==206)) throw AccountRejected(status, if (status == 401) "session_invalid" else "artifact_unavailable")
            if (connection.contentType?.substringBefore(';') != "application/pdf" ||
                connection.getHeaderField("ETag") != "\"${artifact.hash}\"")
                throw TeamDownloadCorrupt("乐谱文件信息不一致，请刷新后重试")
            if(status==206) {
                if(connection.getHeaderField("Content-Range")!="bytes $offset-${artifact.size-1}/${artifact.size}" ||
                    connection.contentLengthLong!=artifact.size-offset) throw TeamDownloadCorrupt("续传范围不一致，未拼接文件")
                check(target.length()==offset) { "暂存文件已变化，请重试" }
            } else {
                if(connection.contentLengthLong!=artifact.size || connection.getHeaderField("Content-Range")!=null)
                    throw TeamDownloadCorrupt("乐谱文件大小不一致，未加入曲库")
                // A server may ignore Range. A validated full response replaces the private partial;
                // it must never be appended to bytes from a previous response.
                offset=0;digest=MessageDigest.getInstance("SHA-256");progress(0,artifact.size)
            }
            var size = offset
            connection.inputStream.use { input -> java.io.FileOutputStream(target,offset>0).use { output ->
                val buffer = ByteArray(64 * 1024)
                try { while (true) {
                    ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > artifact.size) throw TeamDownloadCorrupt("下载文件大小异常")
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    progress(size,artifact.size)
                } } finally { output.fd.sync() }
            } }
            if (size != artifact.size) throw TeamSyncFailure("连接中断，未完成的乐谱可继续下载")
            if (checksum()!=artifact.hash) throw TeamDownloadCorrupt("乐谱校验失败，请重新下载")
        } finally { connection.disconnect() }
    }
}
