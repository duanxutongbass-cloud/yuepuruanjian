package com.ensemble.scorelibrary.sync

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Account-private immutable generations; the small atomic index commits last. */
internal class TeamPublishJournal(private val root: File) {
    fun directory(key: String): File {
        require(UUID.fromString(key).toString() == key)
        return File(root, key).apply {
            check(isDirectory || mkdirs())
            require(!Files.isSymbolicLink(toPath()) && canonicalFile.parentFile == root.canonicalFile)
        }
    }
    fun save(job: TeamPublishJob) = saveBatch(listOf(job))

    fun saveBatch(jobs: List<TeamPublishJob>) {
        require(jobs.isNotEmpty() && jobs.map { it.accountKey }.distinct().size == 1)
        require(jobs.map { it.uploadRequest }.distinct().size == jobs.size)
        val directory = directory(jobs.first().accountKey)
        // Resolve any old AtomicFile backup before replacing its base index.
        readBatch(jobs.first().accountKey)
        val generation = File(directory, UUID.randomUUID().toString())
        check(generation.mkdir())
        var installed = false
        try {
            val chunks = jobs.chunked(50)
            chunks.forEachIndexed { index, chunk ->
                val bytes = JSONObject().put("jobs", JSONArray(chunk.map(::encode))).toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= 512 * 1024)
                FileOutputStream(File(generation, "$index.json")).use { it.write(bytes); it.fd.sync() }
            }
            val index = JSONObject().put("version", 2).put("generation", generation.name)
                .put("chunks", chunks.size).put("count", jobs.size).toString().toByteArray(Charsets.UTF_8)
            val target = AtomicFile(File(directory, "request.json"))
            val pending = File(directory, "index-${UUID.randomUUID()}.tmp")
            try {
                FileOutputStream(pending).use { it.write(index); it.fd.sync() }
                // No non-atomic fallback. If the filesystem cannot replace atomically,
                // keep the previous committed index and refuse the state update.
                Files.move(pending.toPath(), target.baseFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                installed = true
                check(target.readFully().contentEquals(index)) { "上传队列索引写入未确认，保留记录供重试" }
            } finally { pending.delete() }
            cleanGenerations(directory, generation)
        } finally { if (!installed) removeGeneration(directory, generation) }
    }
    private fun encode(job: TeamPublishJob): JSONObject {
        require(!job.completed || !job.cancelled)
        require(UUID.fromString(job.uploadRequest).toString() == job.uploadRequest &&
            UUID.fromString(job.publicationRequest).toString() == job.publicationRequest)
        require(job.file.canonicalFile.parentFile == directory(job.accountKey).canonicalFile)
        require(job.file.name.matches(Regex("[a-f0-9-]{36}\\.pdf")))
        job.details.validate()
        val d = job.details
        return JSONObject().put("file", job.file.name).put("name", job.name).put("hash", job.hash)
            .put("size", job.size).put("pages", job.pages).put("title", d.title).put("part", d.part)
            .put("number", d.number).put("category", d.category).put("recipients", JSONArray(d.recipients.sorted()))
            .put("uploadRequest", job.uploadRequest).put("publicationRequest", job.publicationRequest)
            .put("completed", job.completed).put("cancelled", job.cancelled)
    }
    fun read(key: String): TeamPublishJob? = readBatch(key).singleOrNull()
    fun readBatch(key: String): List<TeamPublishJob> {
        val directory = directory(key)
        val target = AtomicFile(File(directory, "request.json"))
        if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) return emptyList()
        val bytes = target.readFully().also { require(it.size <= 512 * 1024) }
        val index = JSONObject(bytes.toString(Charsets.UTF_8))
        if (!index.has("version")) {
            val rows = index.getJSONArray("jobs")
            require(rows.length() in 1..50)
            return (0 until rows.length()).map { decode(key, rows.getJSONObject(it)) }
        }
        require(index.getInt("version") == 2)
        val name = index.getString("generation").also { require(UUID.fromString(it).toString() == it) }
        val generation = File(directory, name)
        require(generation.isDirectory && !Files.isSymbolicLink(generation.toPath()) && generation.canonicalFile.parentFile == directory.canonicalFile)
        val count = index.getInt("count").also { require(it > 0) }
        val chunks = index.getInt("chunks").also { require(it == (count - 1) / 50 + 1) }
        require(generation.listFiles()?.size == chunks)
        val result = mutableListOf<TeamPublishJob>()
        repeat(chunks) { n ->
            val file = File(generation, "$n.json")
            require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() <= 512 * 1024)
            val rows = JSONObject(file.readText(Charsets.UTF_8)).getJSONArray("jobs")
            require(rows.length() == minOf(50, count - result.size))
            repeat(rows.length()) { result += decode(key, rows.getJSONObject(it)) }
        }
        require(result.size == count && result.map { it.uploadRequest }.distinct().size == count)
        return result
    }
    private fun decode(key: String, json: JSONObject): TeamPublishJob {
        val name = json.getString("file").also { require(it.matches(Regex("[a-f0-9-]{36}\\.pdf"))) }
        val ids = json.getJSONArray("recipients")
        require(ids.length() in 1..100)
        val details = TeamPublishDetails(json.getString("title"), json.getString("part"), json.getString("number"),
            json.getString("category"), (0 until ids.length()).map { ids.getString(it) }.toSet()).also { it.validate() }
        val completed = json.getBoolean("completed")
        val cancelled = json.optBoolean("cancelled", false)
        require(!completed || !cancelled)
        return TeamPublishJob(key, File(directory(key), name), json.getString("name"), json.getString("hash"),
            json.getLong("size"), json.getInt("pages"), details, json.getString("uploadRequest"),
            json.getString("publicationRequest"), completed, cancelled).also { validateFile(it) }
    }
    fun discardStaged(job: TeamPublishJob) {
        require(!job.finished && readBatch(job.accountKey).isEmpty()) { "已有上传记录，请先核验或取消未完成队列" }
        validateFile(job)
        check(!job.file.exists() || job.file.delete())
    }
    fun forget(job: TeamPublishJob) = forgetBatch(listOf(job))
    fun forgetBatch(jobs: List<TeamPublishJob>, cancelled: Boolean = false) {
        require(jobs.isNotEmpty() && (cancelled || jobs.all { it.finished }) && jobs.map { it.accountKey }.distinct().size == 1)
        jobs.forEach(::validateFile)
        val directory = directory(jobs.first().accountKey)
        require(readBatch(jobs.first().accountKey).map { it.uploadRequest }.toSet() == jobs.map { it.uploadRequest }.toSet())
        AtomicFile(File(directory, "request.json")).delete()
        jobs.forEach { check(!it.file.exists() || it.file.delete()) }
        cleanGenerations(directory, null)
    }
    private fun validateFile(job: TeamPublishJob) {
        require(job.file.canonicalFile.parentFile == directory(job.accountKey).canonicalFile &&
            job.file.name.matches(Regex("[a-f0-9-]{36}\\.pdf")) && !Files.isSymbolicLink(job.file.toPath()))
    }
    private fun cleanGenerations(directory: File, keep: File?) {
        directory.listFiles()?.filter { it != keep && runCatching { UUID.fromString(it.name).toString() == it.name }.getOrDefault(false) }
            ?.forEach { removeGeneration(directory, it) }
    }
    private fun removeGeneration(directory: File, generation: File) {
        if (!generation.isDirectory || Files.isSymbolicLink(generation.toPath()) || generation.canonicalFile.parentFile != directory.canonicalFile) return
        generation.listFiles()?.filter { it.name.matches(Regex("[0-9]+\\.json")) && !Files.isSymbolicLink(it.toPath()) && it.isFile }
            ?.forEach { it.delete() }
        generation.delete() // No recursive deletion: unexpected files remain intact.
    }
}
