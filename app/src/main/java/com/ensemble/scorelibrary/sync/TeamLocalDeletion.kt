package com.ensemble.scorelibrary.sync

import androidx.room.withTransaction
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal data class TeamLocalDeletionResult(val versions: Set<String>, val removedLocalPieces: Set<String>,
    val concerts: Set<String>, val removedFiles: Int)

/** Last local cleanup phase, after persistent drafts/folders/offline vault cleanup.
 * Does not acknowledge the inbox or release the retirement barrier; the coordinator owns both.
 */
internal class TeamLocalDeletion(private val removeFile: (File) -> Boolean = { it.delete() }) {
    private data class Managed(val file: File, val hash: String?, val size: Long)
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes = ByteArray(65536);while (true) {
            val n = input.read(bytes);if (n < 0) break;digest.update(bytes, 0, n)
        } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun verify(row: Managed, directory: File) {
        val file = row.file
        val parent = file.parentFile
        require(file.isAbsolute && file.canonicalFile == File(directory.canonicalFile,file.name).absoluteFile && parent != null && parent.canonicalFile == directory.canonicalFile &&
            file.name.matches(Regex("(?:[a-f0-9-]{36}\\.pdf|resume-[a-f0-9-]{36}\\.part)"))) { "删除目标不是本账号的受管文件" }
        if (!file.exists()) return
        require(file.isFile && if (row.hash == null) file.length() in 0..row.size else file.length() == row.size && hash(file) == row.hash) {
            "受管文件内容已变化，原文件保留，请核对后重试"
        }
    }
    suspend fun purge(c: TeamCollection, ids: Set<String>, journal: TeamDeletionJournal): TeamLocalDeletionResult {
        require(ids.size in 1..100 && ids.all { UUID.fromString(it).toString() == it })
        val session = checkNotNull(c.session)
        require(session.server == journal.server && session.principal.teamId == journal.team &&
            c.key == teamId("${session.server}/${session.principal.teamId}/${session.principal.accountId}") &&
            c.member == teamId("${c.key}/member/${session.principal.memberId}")) { "删除账号或团队来源不匹配" }
        require(c.deletionChanging && journal.read().retired.containsAll(ids)) { "必须先持久接收删除记录并阻止旧内容读写" }
        val owner = checkNotNull(c.directory.parentFile)
        require(c.directory.name == "scores" && owner.name == c.key && c.directory.canonicalFile == c.directory.absoluteFile)
        return c.database.withTransaction {
            c.database.openHelper.writableDatabase.query("PRAGMA secure_delete=ON").use {
                check(it.moveToFirst() && it.getInt(0) == 1) { "数据库删除设置无法核对，尚未清理" }
            }
            val old = c.database.resolutionDao().snapshot()
            val dao = c.database.teamDeletionDao()
            val checkpoint = journal.read()
            val versionIds = ids.map { teamId("version/$it") }.toSet()
            val pdfIds = ids.map { teamId("pdf/$it") }.toSet()
            val targets = old.versions.filter { it.id in versionIds || it.scorePdfId in pdfIds || it.originalUri in ids.map { id -> "team-artifact:$id" } }
            targets.forEach { v ->
                val aid = ids.singleOrNull { v.id == teamId("version/$it") }
                require(aid != null && v.scorePdfId == teamId("pdf/$aid") && v.originalUri == "team-artifact:$aid" &&
                    v.fileHash.matches(Regex("[a-f0-9]{64}")) && v.fileSizeBytes > 0 && v.pageCount in 1..10000) { "本地分谱身份不一致，未删除" }
            }
            val versions = targets.map { it.id }.toSet()
            val files = mutableListOf<Managed>()
            targets.forEach { v ->
                files += Managed(File(v.managedFilePath), v.fileHash, v.fileSizeBytes)
                old.caches.filter { it.scorePdfVersionId == v.id }.mapNotNull { it.cachedFilePath }.forEach { path -> files += Managed(File(path), v.fileHash, v.fileSizeBytes) }
                val aid = ids.single { v.id == teamId("version/$it") }
                files += Managed(File(c.directory, "resume-${teamId("partial/$aid/${v.fileHash}/${v.fileSizeBytes}/${v.pageCount}")}.part"), null, v.fileSizeBytes)
            }
            require(files.groupBy { it.file.absolutePath }.values.all { rows -> rows.distinct().size == 1 }) { "受管文件存在冲突引用，未删除" }
            val managed = files.distinct()
            val protected = (old.versions.filter { it.id !in versions }.map { it.managedFilePath } +
                old.caches.filter { it.scorePdfVersionId !in versions }.mapNotNull { it.cachedFilePath }).map { File(it).canonicalFile }.toSet()
            require(managed.none { it.file.canonicalFile in protected } && old.pdfs.none { it.id !in pdfIds && it.activeVersionId in versions }) {
                "删除目标仍被其他分谱引用，原文件保留"
            }
            managed.forEach { verify(it, c.directory) }
            val snapshots = dao.snapshots()
            val affected = snapshots.filter { snapshot ->
                require(snapshot.payload.toByteArray(Charsets.UTF_8).size <= MAX_TEAM_SNAPSHOT)
                val payload = JSONObject(snapshot.payload)
                val keys = payload.keys().asSequence().toList()
                require(keys.size <= 10000 && keys.all { it.matches(Regex("[a-f0-9-]{36}:[0-9]{1,5}")) }) { "冻结内容无法核对，尚未删除" }
                val freeze = snapshot.manifest.takeIf { it.startsWith("team-freeze-v1|") }?.split('|', limit = 6)?.getOrNull(1)
                keys.any { it.substringBefore(':') in versionIds } || freeze in checkpoint.freezes
            }.map { it.concertId }.toMutableSet()
            checkpoint.programs.forEach { affected += teamId("concert/$it") }
            val touchedParts = old.bindings.filter { it.scorePdfId in pdfIds }.map { it.piecePartId }.toSet()
            val emptyParts = touchedParts.filter { part -> old.bindings.none { it.piecePartId == part && it.scorePdfId !in pdfIds } }.toSet()
            val touchedPieces = old.parts.filter { it.id in emptyParts }.map { it.pieceId }.toSet()
            val emptyPieces = touchedPieces.filter { piece -> old.parts.none { it.pieceId == piece && it.id !in emptyParts } }.toSet()
            affected += old.items.filter { it.pieceId in emptyPieces }.map { it.concertId }
            // Preflight all targets before the first unlink. Keep Room paths until
            // all files are gone, so a failed transaction can retry missing files.
            var removed = 0
            managed.forEach { row ->
                require(c.deletionChanging && journal.read().retired.containsAll(ids))
                verify(row, c.directory)
                if (row.file.exists()) { check(removeFile(row.file) && !row.file.exists()) { "文件清理未完成，请重试" };removed++ }
            }
            affected.toList().chunked(200).forEach { group -> dao.snapshots(group);dao.concertItems(group);dao.concerts(group) }
            val vv = versions.toList();val pp = pdfIds.toList();val parts = emptyParts.toList();val pieces = emptyPieces.toList()
            dao.revisions(vv);dao.cloud(vv);dao.pages(vv);dao.caches(vv);dao.bindings(pp);dao.versions(vv);dao.pdfs(pp)
            dao.permissions(parts);dao.assignments(parts);dao.parts(parts)
            dao.pieceItems(pieces);dao.movements(pieces);dao.pieces(pieces)
            require(c.deletionChanging && journal.read().retired.containsAll(ids))
            TeamLocalDeletionResult(versions, emptyPieces, affected, removed)
        }
    }
}
