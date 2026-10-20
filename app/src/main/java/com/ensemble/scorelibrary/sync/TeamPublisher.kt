package com.ensemble.scorelibrary.sync

import com.ensemble.scorelibrary.domain.ScoreNumber

import com.ensemble.scorelibrary.account.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.inject.Inject

internal const val MAX_TEAM_UPLOAD = 100L * 1024 * 1024

data class TeamPublishDetails(val title: String, val part: String, val number: String, val category: String,
    val recipients: Set<String>) {
    fun validate() {
        require(listOf(title, part, category).all { it.isNotBlank() && it.length <= 100 && it.none(Char::isISOControl) }) {
            "请填写曲名、乐器和分类（各不超过 100 字）"
        }
        ScoreNumber.parse(number)
        require(!number.startsWith("09")) { "总谱加分谱组合文件不能作为独立分谱发布，请先拆分" }
        require(recipients.size in 1..100 && recipients.all { UUID.fromString(it).toString() == it }) { "请选择接收成员（每份最多 100 位）" }
    }
}

data class TeamPublishJob(val accountKey: String, val file: File, val name: String, val hash: String,
    val size: Long, val pages: Int, val details: TeamPublishDetails,
    val uploadRequest: String = UUID.randomUUID().toString(), val publicationRequest: String = UUID.randomUUID().toString(),
    val completed: Boolean = false, val cancelled: Boolean = false) {
    val finished: Boolean get() = completed || cancelled
}

internal class UploadCatalogConflict(val latest: List<ExistingTeamScore>, message: String) : IllegalStateException(message)

data class ExistingTeamScore(val number: String, val title: String, val category: String, val part: String, val removed: Boolean, val artifactId: String = "")

class TeamPublisher @Inject constructor(private val gateway: TeamAccountGateway) {
    fun inventory(session: TeamSession) = TeamNumberInventory.parse(gateway.scoreRequest(session, "/v1/admin/number-inventory"))
    fun numberingContext(session: TeamSession): Pair<List<ExistingTeamScore>, TeamNumberInventory> {
        val catalog = gateway.scoreRequest(session, "/v1/admin/artifacts")
        val inventory = inventory(session)
        require(catalog.getLong("revision") == inventory.revision) { "检查编号期间曲库已变化，请重新选择核对" }
        return existingRows(catalog.getJSONArray("artifacts")) to inventory
    }
    fun existing(session: TeamSession): List<ExistingTeamScore> {
        val rows = gateway.scoreRequest(session, "/v1/admin/artifacts").getJSONArray("artifacts")
        return existingRows(rows)
    }
    private fun existingRows(rows: JSONArray): List<ExistingTeamScore> {
        require(rows.length() <= 5000)
        return (0 until rows.length()).map { rows.getJSONObject(it) }
            .filter { it.optBoolean("published") || it.optBoolean("removed") }
            .filter { it.optString("scoreNumber").isNotEmpty() }.onEach { ScoreNumber.parse(it.getString("scoreNumber")) }
            .map { ExistingTeamScore(it.getString("scoreNumber"), it.getString("title"), it.optString("category"), it.getString("partName"), it.optBoolean("removed"), it.optString("artifactId")) }
    }
    internal suspend fun comparisonArtifact(session: TeamSession, score: ExistingTeamScore): TeamArtifact {
        require(!score.removed) { "回收站乐谱须恢复并取得阅读授权后才能对比" }
        return TeamScoreClient(gateway).catalog(session).artifacts.singleOrNull {
            (score.artifactId.isEmpty() || it.id == score.artifactId) && it.number == score.number &&
                it.title == score.title && it.category == score.category && it.partName == score.part
        } ?: throw IllegalStateException("当前账号尚无本份阅读授权，请先分配后再对比")
    }
    internal suspend fun downloadComparison(session: TeamSession, artifact: TeamArtifact, target: File) {
        TeamScoreClient(gateway).download(session, artifact, target)
    }
    internal suspend fun verifyComparison(session: TeamSession, artifact: TeamArtifact) {
        check(TeamScoreClient(gateway).catalog(session).artifacts.singleOrNull { it.id == artifact.id } == artifact) {
            "原谱授权或内容已变化，请关闭后重新核验"
        }
    }
    fun cancel(session: TeamSession, jobs: List<TeamPublishJob>, guard: () -> Unit = {}, cancelled: (List<TeamPublishJob>) -> Unit = {}): Boolean {
        validateBatch(session, jobs)
        var state = jobs
        var confirmed = true
        teamPublicationChunks(jobs).forEach { chunk ->
            if (chunk.jobs.all { it.finished }) return@forEach
            require(chunk.jobs.none { it.finished }) { "本批完成状态不一致，请保留记录重新核验" }
            guard()
            val reply = gateway.scoreRequest(session, "/v1/admin/publication-cancellations", JSONObject()
                .put("publicationRequest", chunk.requestId).put("uploadRequests", JSONArray(chunk.jobs.map { it.uploadRequest })))
            if (reply.getBoolean("cancelled")) {
                val ids = chunk.jobs.map { it.uploadRequest }.toSet()
                state = state.map { if (it.uploadRequest in ids) it.copy(cancelled = true) else it }
                guard(); cancelled(state)
            } else confirmed = false
        }
        return confirmed
    }
    fun publish(session: TeamSession, job: TeamPublishJob, progress: (Long, Long) -> Unit) {
        publishBatch(session, listOf(job), progress = progress)
    }
    fun publishBatch(session: TeamSession, jobs: List<TeamPublishJob>, committed: (List<TeamPublishJob>) -> Unit = {},
        guard: () -> Unit = {}, progress: (Long, Long) -> Unit) {
        validateBatch(session, jobs)
        jobs.filterNot { it.finished }.forEach { job -> require(job.size in 1..MAX_TEAM_UPLOAD && job.file.length() == job.size && TeamLibrarySync.hash(job.file) == job.hash) {
            "待上传文件缺失或已改变，未发布"
        } }
        var state = jobs
        var completedBytes = jobs.filter { it.completed }.sumOf { it.size }
        val total = jobs.filterNot { it.cancelled }.sumOf { it.size }
        teamPublicationChunks(jobs).forEach { chunk ->
            if (chunk.jobs.all { it.finished }) return@forEach
            require(chunk.jobs.none { it.finished }) { "本批完成状态不一致，请保留记录重新核验" }
            guard()
            publishValidated(session, chunk.jobs, chunk.requestId, guard) { sent, _ -> progress(completedBytes + sent, total) }
            val ids = chunk.jobs.map { it.uploadRequest }.toSet()
            state = state.map { if (it.uploadRequest in ids) it.copy(completed = true) else it }
            completedBytes += chunk.jobs.sumOf { it.size }
            guard(); committed(state)
        }
    }
    fun validateBatch(session: TeamSession, jobs: List<TeamPublishJob>) {
        require(jobs.isNotEmpty()) { "请至少选择一份 PDF" }
        require(jobs.map { it.uploadRequest }.distinct().size == jobs.size) { "上传记录重复，请保留队列重新核验" }
        require(session.principal.role == "admin") { "仅团队管理员可以上传乐谱" }
        require(session.expiresAt > System.currentTimeMillis() / 1000) { "登录已过期，请重新登录" }
        jobs.forEach { job ->
        require(!job.completed || !job.cancelled)
        require(UUID.fromString(job.uploadRequest).toString() == job.uploadRequest && UUID.fromString(job.publicationRequest).toString() == job.publicationRequest)
        require(job.accountKey == teamId(session.server + "/" + session.principal.teamId + "/" + session.principal.accountId))
        job.details.validate()
        require(session.principal.numberingProtocol == 2 || ScoreNumber.parse(job.details.number).namingVersion == 1) { "当前服务仅支持001–999，请重新核验" }
        require(session.principal.accountId in job.details.recipients) { "请保留当前管理员的阅读权限" }
        }
        jobs.groupBy { ScoreNumber.parse(it.details.number).sequence }.values.forEach { group ->
            require(group.map { Triple(it.details.title, it.details.category, ScoreNumber.parse(it.details.number).category) }.distinct().size == 1) {
                "同批次中同一序号的曲名或分类冲突，请核对后再发布"
            }
            require(group.map { it.details.number.take(2) }.distinct().size == group.size) { "同批次中有同号同乐器的重复分谱，请核对" }
        }
        teamPublicationChunks(jobs)
    }
    private fun publishValidated(session: TeamSession, jobs: List<TeamPublishJob>, requestId: String,
        guard: () -> Unit, progress: (Long, Long) -> Unit) {
        val preparedJobs = jobs.map { job ->
        guard()
        val d = job.details
        val prepared = gateway.scoreRequest(session, "/v1/admin/uploads", JSONObject().put("requestId", job.uploadRequest)
            .put("title", d.title).put("partName", d.part).put("scoreNumber", d.number).put("category", d.category)
            .put("sha256", job.hash).put("size", job.size).put("independentSource", true))
        val upload = prepared.getString("uploadId").also { require(UUID.fromString(it).toString() == it) }
        Triple(job, upload, prepared.getBoolean("complete"))
        }
        guard()
        val rows = gateway.scoreRequest(session, "/v1/admin/artifacts").getJSONArray("artifacts")
        checkRows(rows, jobs, preparedJobs.map { it.second }.toSet())
        val assignments = JSONArray(preparedJobs.map { (job, id, _) -> JSONObject().put("artifactId", id).put("accountIds", JSONArray(job.details.recipients.sorted())) })
        val body = JSONObject().put("requestId", requestId).put("assignments", assignments)
        var completedBytes = 0L
        val total = jobs.sumOf { it.size }
        preparedJobs.forEach { (job, id, complete) ->
            guard()
            if (!complete) upload(session, id, job) { sent, _ -> progress(completedBytes + sent, total) }
            completedBytes += job.size
        }
        guard()
        val receipt = gateway.scoreRequest(session, "/v1/admin/publications", body)
        require(receipt.getBoolean("ok") && receipt.getString("publicationId") == requestId &&
            receipt.getJSONArray("artifactIds").length() == jobs.size && preparedJobs.indices.all {
                receipt.getJSONArray("artifactIds").getString(it) == preparedJobs[it].second
            }) { "发布回执不匹配，请重试核验" }
    }
    fun checkNewBatch(session: TeamSession, jobs: List<TeamPublishJob>) {
        validateBatch(session, jobs)
        val rows = gateway.scoreRequest(session, "/v1/admin/artifacts").getJSONArray("artifacts")
        try { checkRows(rows, jobs, emptySet()) }
        catch (conflict: IllegalArgumentException) {
            throw UploadCatalogConflict(existingRows(rows), "曲库已变化，请重新核对自动编号和声部并确认预览：${conflict.message}")
        }
    }
    private fun checkRows(rows: JSONArray, jobs: List<TeamPublishJob>, ignore: Set<String>) {
        require(rows.length() <= 5000)
        jobs.forEach { job ->
        val d = job.details
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val number = row.optString("scoreNumber")
            if (row.getString("artifactId") in ignore || !row.optBoolean("published") && !row.optBoolean("removed") || number.isEmpty() || ScoreNumber.parse(number).sequence != ScoreNumber.parse(d.number).sequence) continue
            require(row.getString("title") == d.title && row.optString("category") == d.category && ScoreNumber.parse(number).category == ScoreNumber.parse(d.number).category) {
                "此序号已有不同曲名或分类，请核对原编号，不会自动合并"
            }
            require(number.take(2) != d.number.take(2)) { "此曲目已有同乐器分谱，请先核对已有乐谱；本次不会覆盖或自动创建新版" }
        }
        }
    }
    private fun upload(session: TeamSession, id: String, job: TeamPublishJob, progress: (Long, Long) -> Unit) {
        if (session.offline) throw OfflineUnavailable("请联网核验后上传乐谱")
        gateway.requireScoreWrite(session)
        val connection = URL(accountServer(session.server) + session.scorePath("/v1/admin/uploads/$id/content")).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8000; connection.readTimeout = 30_000
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${session.token}")
            connection.setRequestProperty("X-Score-Team",session.principal.teamId)
            connection.setRequestProperty("X-Device-Id", gateway.device)
            connection.setRequestProperty("Content-Type", "application/pdf")
            connection.setFixedLengthStreamingMode(job.size)
            var sent = 0L
            job.file.inputStream().use { input -> connection.outputStream.use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    output.write(buffer, 0, count); sent += count; progress(sent, job.size)
                }
            } }
            require(sent == job.size)
            val status = connection.responseCode
            val stream = if (status == 200) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    require(output.size() + n <= 16384)
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            } ?: byteArrayOf()
            require(bytes.size <= 16384)
            if (status != 200) throw AccountRejected(status, runCatching { JSONObject(bytes.toString(Charsets.UTF_8)).optString("error") }.getOrDefault(""))
            val reply = JSONObject(bytes.toString(Charsets.UTF_8))
            require(reply.getString("artifactId") == id && reply.getString("hash") == job.hash && reply.getLong("size") == job.size) {
                "上传回执不匹配，未确认发布"
            }
        } finally { connection.disconnect() }
    }
}
