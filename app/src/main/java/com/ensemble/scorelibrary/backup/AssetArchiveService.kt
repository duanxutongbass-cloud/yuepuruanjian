package com.ensemble.scorelibrary.backup

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.room.withTransaction
import com.ensemble.scorelibrary.annotation.InkCodec
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.core.model.MemberRole
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import javax.inject.Inject
import javax.inject.Singleton

open class ArchivePdfVerifier @Inject constructor() {
    open suspend fun verify(file: File, pages: Int) {
        require(pages in 1..10_000)
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                require(renderer.pageCount == pages) { "PDF 实际页数与备份不一致" }
                repeat(pages) { index ->
                    currentCoroutineContext().ensureActive()
                    renderer.openPage(index).use { require(it.width > 0 && it.height > 0) }
                }
            }
        }
    }
}

interface AssetArchiveStore {
    suspend fun export(member: String, kind: ArchiveKind, destination: Uri)
    suspend fun preview(source: Uri): ArchivePreview
    suspend fun restore(member: String?, source: Uri, expectedHash: String)
}

@Singleton
class AssetArchiveService @Inject constructor(@param:ApplicationContext private val context: Context,
    private val database: ScoreLibraryDatabase, private val verifier: ArchivePdfVerifier) : AssetArchiveStore {
    private val mutex = Mutex()
    private suspend fun member(id: String, admin: Boolean = false) {
        require(id != "shared") { "成员身份不能占用共享批注保留标识" }
        val member = database.resolutionDao().members().singleOrNull { it.id == id && !it.isArchived }
        check(member != null && (!admin || member.role == MemberRole.ADMIN)) { "当前成员无权执行此备份操作" }
    }
    private fun temp() = File(context.cacheDir, "archive-${UUID.randomUUID()}").apply { check(mkdirs()) }
    private suspend fun sourceFile(uri: Uri, directory: File): File {
        require(uri.scheme == "content") { "请通过系统文件选择器选择备份" }
        return File(directory, "input.zip").also { file ->
            context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output ->
                ArchiveFormat.copy(input, output, ArchiveFormat.TOTAL_LIMIT); output.fd.sync()
            } } ?: error("无法读取备份文件")
        }
    }
    override suspend fun export(member: String, kind: ArchiveKind, destination: Uri) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val directory = temp()
            try {
                val file = File(directory, "export.zip")
                writeArchive(member, kind, file)
                require(destination.scheme == "content")
                // Keep authorization and the external write within one consistent transaction.
                database.withTransaction {
                    member(member, kind == ArchiveKind.LIBRARY)
                    context.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                        file.inputStream().use { ArchiveFormat.copy(it, output, ArchiveFormat.TOTAL_LIMIT) }
                        output.flush()
                    } ?: error("无法写入备份文件")
                }
                val written = context.contentResolver.openInputStream(destination)?.use {
                    ArchiveFormat.copy(it, null, ArchiveFormat.TOTAL_LIMIT).second
                } ?: error("无法重新校验导出的备份")
                check(written == ArchiveFormat.hash(file)) { "导出文件校验失败，不能作为有效备份" }
            } finally { directory.deleteRecursively() }
        }
    }
    internal suspend fun writeArchive(actor: String, kind: ArchiveKind, output: File) = withContext(Dispatchers.IO) {
        val root = database.withTransaction {
            member(actor, kind == ArchiveKind.LIBRARY)
            val sql = database.openHelper.writableDatabase
            val tables = JSONObject()
            var metadataBytes = 0L
            if (kind == ArchiveKind.LIBRARY) for (table in ArchiveFormat.tables) {
                val rows = when (table) {
                    "annotation_pages" -> ArchiveFormat.read(sql, table, "WHERE ownerKey = 'shared'")
                    "annotation_revisions" -> ArchiveFormat.read(sql, table,
                        "WHERE documentId IN (SELECT id FROM annotation_pages WHERE ownerKey = 'shared')")
                    else -> ArchiveFormat.read(sql, table)
                }
                metadataBytes += rows.toString().toByteArray().size
                require(metadataBytes <= ArchiveFormat.JSON_LIMIT) { "备份元数据过大" }
                tables.put(table, rows)
            } else tables.put("annotation_pages", ArchiveFormat.read(sql, "annotation_pages", "WHERE ownerKey = ?", arrayOf(actor)))
            JSONObject().put("format", ArchiveFormat.NAME).put("formatVersion", 3).put("databaseVersion", 5)
                .put("kind", kind.name).put("owner", actor).put("createdAtEpochMillis", System.currentTimeMillis())
                .put("tables", tables).put("files", JSONArray()).put("versionReferences", JSONArray().apply {
                    if (kind == ArchiveKind.PERSONAL) {
                        val ids = ArchiveFormat.rows(tables.getJSONArray("annotation_pages")).map { it.getString("versionId") }.toSet()
                        database.resolutionDao().versions().filter { it.id in ids }.forEach {
                            put(JSONObject().put("id", it.id).put("sha256", it.fileHash).put("pageCount", it.pageCount))
                        }
                        check(length() == ids.size) { "个人批注引用的版本缺失" }
                    }
                })
        }
        var total = 0L
        ZipOutputStream(output.outputStream()).use { zip ->
            if (kind == ArchiveKind.LIBRARY) {
                val versions = ArchiveFormat.rows(root.getJSONObject("tables").getJSONArray("score_pdf_versions"))
                require(versions.size <= 5000)
                for (row in versions) {
                    currentCoroutineContext().ensureActive()
                    val path = row.getString("managedFilePath")
                    val file = (if (File(path).isAbsolute) File(path) else File(context.filesDir, path)).canonicalFile
                    check(file.path.startsWith(context.filesDir.canonicalPath + File.separator) && file.isFile) { "原始 PDF 缺失，母库不能完整导出" }
                    verifier.verify(file, row.getInt("pageCount"))
                    val entry = ArchiveFormat.entry(row.getString("id"))
                    zip.putNextEntry(ZipEntry(entry))
                    val (bytes, hash) = file.inputStream().use { ArchiveFormat.copy(it, zip, ArchiveFormat.PDF_LIMIT) }
                    zip.closeEntry()
                    require(bytes == row.getLong("fileSizeBytes") && hash.equals(row.getString("fileHash"), true)) { "原始 PDF 完整性校验失败" }
                    total += bytes; require(total <= ArchiveFormat.TOTAL_LIMIT)
                    root.getJSONArray("files").put(JSONObject().put("versionId", row.getString("id"))
                        .put("entry", entry).put("bytes", bytes).put("sha256", hash))
                    row.put("managedFilePath", entry)
                }
            }
            val manifest = root.toString(2).toByteArray(Charsets.UTF_8)
            require(manifest.size <= ArchiveFormat.JSON_LIMIT) { "备份元数据超过 16 MiB 安全上限" }
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest); zip.closeEntry()
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(("Ensemble Score Library archive v3 / database 5. UTF-8 JSON + original PDFs, including organizer categories and numbers.\n" +
                "LIBRARY excludes all personal annotations. PERSONAL contains one owner's annotations and no PDFs.\n" +
                "Restore LIBRARY into an empty installation, then restore each owner's PERSONAL archive.\n" +
                "IDs, source page indexes and version hashes must match. Existing conflicting data is never overwritten.\n" +
                "Cache state and device/login/pedal settings are not assets and are not restored.\n" +
                "This archive is unencrypted. Keep independent private copies. SHA-256 detects corruption, not authorship.\n").toByteArray())
            zip.closeEntry()
        }
        require(output.length() <= ArchiveFormat.TOTAL_LIMIT)
        val checkDir = temp()
        try { val checked = readArchive(output, checkDir); validatePackage(checked) }
        finally { checkDir.deleteRecursively() }
    }
    private data class Package(val root: JSONObject, val files: Map<String, File>, val hash: String) {
        val kind get() = ArchiveKind.valueOf(root.getString("kind"))
        val owner get() = root.getString("owner")
        val tables get() = root.getJSONObject("tables")
    }
    private suspend fun readEntry(zip: ZipFile, entry: ZipEntry, output: java.io.OutputStream?, limit: Long): Pair<Long, String> {
        val crc = CRC32()
        val result = CheckedInputStream(zip.getInputStream(entry), crc).use { ArchiveFormat.copy(it, output, limit) }
        require(crc.value == entry.crc) { "备份条目完整性校验失败" }
        return result
    }
    private suspend fun readArchive(file: File, directory: File): Package {
        val hash = ArchiveFormat.hash(file)
        ZipFile(file).use { zip ->
            require(zip.size() <= 5002) { "备份条目过多" }
            val entries = zip.entries().asSequence().toList()
            require(entries.size <= 5002 && entries.map { it.name }.toSet().size == entries.size) { "备份条目重复或过多" }
            require(entries.none { it.isDirectory })
            val manifest = zip.getEntry("manifest.json") ?: error("缺少 manifest.json")
            val buffer = ByteArrayOutputStream()
            readEntry(zip, manifest, buffer, ArchiveFormat.JSON_LIMIT.toLong())
            val text = buffer.toString("UTF-8"); ArchiveFormat.depthCheck(text)
            val root = JSONObject(text)
            require(ArchiveFormat.keys(root) == setOf("format", "formatVersion", "databaseVersion", "kind", "owner", "createdAtEpochMillis", "tables", "files", "versionReferences"))
            val legacy = root.get("formatVersion") == 1 && root.get("databaseVersion") == 3
            require(root.getString("format") == ArchiveFormat.NAME && (legacy || root.get("formatVersion") == 2 && root.get("databaseVersion") == 4 || root.get("formatVersion") == 3 && root.get("databaseVersion") == 5)) { "不支持的备份格式版本" }
            val kind = ArchiveKind.valueOf(root.getString("kind"))
            require(root.getString("owner").isNotBlank() && root.getString("owner") != "shared")
            val tables = root.getJSONObject("tables")
            require(ArchiveFormat.keys(tables) == if (kind == ArchiveKind.LIBRARY) ArchiveFormat.tables.toSet() else setOf("annotation_pages"))
            if (root.get("formatVersion") != 3 && kind == ArchiveKind.LIBRARY) {
                ArchiveFormat.rows(root.getJSONObject("tables").getJSONArray("concerts")).forEach { row ->
                    for(column in listOf("audienceMembers", "audienceParts")) { require(!row.has(column)); row.put(column, JSONObject.NULL) }
                }
            }
            if (legacy && kind == ArchiveKind.LIBRARY) {
                for (row in ArchiveFormat.rows(tables.getJSONArray("pieces"))) {
                    for (column in listOf("categoryCode", "categoryName", "organizerSequence")) {
                        require(!row.has(column)) { "旧版备份含未知整理器字段" }
                        row.put(column, JSONObject.NULL)
                    }
                }
            }
            val records = ArchiveFormat.rows(root.getJSONArray("files"))
            require(records.map { it.getString("versionId") }.toSet().size == records.size)
            val expected = records.map { it.getString("entry") }.toSet() + setOf("manifest.json", "README.txt")
            require(entries.map { it.name }.toSet() == expected) { "备份包含未知或缺失文件" }
            val extracted = mutableMapOf<String, File>(); var total = 0L
            for (record in records) {
                val name = record.getString("entry")
                require(name == ArchiveFormat.entry(record.getString("versionId"))) { "非法备份文件路径" }
                val output = File(directory, "${UUID.randomUUID()}.pdf")
                val (bytes, digest) = output.outputStream().use { target ->
                    readEntry(zip, zip.getEntry(name), target, ArchiveFormat.PDF_LIMIT).also { target.fd.sync() }
                }
                total += bytes; require(total <= ArchiveFormat.TOTAL_LIMIT)
                require(bytes == record.getLong("bytes") && digest.equals(record.getString("sha256"), true)) { "备份 PDF 损坏" }
                extracted[record.getString("versionId")] = output
            }
            readEntry(zip, zip.getEntry("README.txt"), null, 64 * 1024)
            return Package(root, extracted, hash)
        }
    }
    private suspend fun validatePackage(pack: Package) {
        if (pack.kind == ArchiveKind.LIBRARY) {
            require(pack.root.getJSONArray("versionReferences").length() == 0)
            val versions = ArchiveFormat.rows(pack.tables.getJSONArray("score_pdf_versions"))
            require(versions.map { it.getString("id") }.toSet() == pack.files.keys)
            for (row in versions) {
                val file = requireNotNull(pack.files[row.getString("id")])
                require(row.getString("managedFilePath") == ArchiveFormat.entry(row.getString("id")))
                require(file.length() == row.getLong("fileSizeBytes") && ArchiveFormat.hash(file).equals(row.getString("fileHash"), true))
                verifier.verify(file, row.getInt("pageCount"))
            }
            require(ArchiveFormat.rows(pack.tables.getJSONArray("annotation_pages")).all { it.getString("ownerKey") == "shared" }) { "母库包不得含个人批注" }
            val staging = Room.inMemoryDatabaseBuilder(context, ScoreLibraryDatabase::class.java).build()
            try { staging.withTransaction { insertLibrary(staging, pack.tables); validateLibrary(staging) } }
            finally { staging.close() }
        } else {
            require(pack.files.isEmpty()) { "个人批注包不得包含 PDF" }
            val pages = ArchiveFormat.rows(pack.tables.getJSONArray("annotation_pages"))
            val columns = ArchiveFormat.columns(database.openHelper.writableDatabase, "annotation_pages")
            require(pages.map { it.getString("versionId") to it.getInt("pageIndex") }.toSet().size == pages.size)
            for (page in pages) {
                ArchiveFormat.values(page, columns)
                require(page.getString("ownerKey") == pack.owner && page.getLong("generation") >= 0 && page.isNull("publishedRevisionId")) { "个人批注归属无效" }
                require(page.getInt("pageIndex") >= 0)
                ArchiveFormat.depthCheck(page.getString("payload"))
                InkCodec.decode(page.getString("payload"))
            }
            val refs = ArchiveFormat.rows(pack.root.getJSONArray("versionReferences"))
            require(refs.map { it.getString("id") }.toSet().size == refs.size)
            require(refs.map { it.getString("id") }.toSet() == pages.map { it.getString("versionId") }.toSet())
            for (ref in refs) {
                require(ref.getString("sha256").matches(Regex("[a-fA-F0-9]{64}")) && ref.getInt("pageCount") in 1..10_000)
                require(pages.filter { it.getString("versionId") == ref.getString("id") }.all { it.getInt("pageIndex") < ref.getInt("pageCount") })
            }
        }
    }
    private fun insertLibrary(db: ScoreLibraryDatabase, tables: JSONObject) {
        val sql = db.openHelper.writableDatabase
        val pdfs = tables.getJSONArray("score_pdfs")
        val active = ArchiveFormat.rows(pdfs).associate { it.getString("id") to it.opt("activeVersionId") }
        val deferred = JSONArray(pdfs.toString())
        ArchiveFormat.rows(deferred).forEach { it.put("activeVersionId", JSONObject.NULL) }
        for (table in ArchiveFormat.tables) ArchiveFormat.insert(sql, table, if (table == "score_pdfs") deferred else tables.getJSONArray(table))
        for ((id, version) in active) if (version != JSONObject.NULL)
            sql.execSQL("UPDATE score_pdfs SET activeVersionId = ? WHERE id = ?", arrayOf(version, id))
        sql.query("PRAGMA foreign_key_check").use { require(!it.moveToNext()) { "备份存在无效外键" } }
    }
    override suspend fun preview(source: Uri): ArchivePreview = withContext(Dispatchers.IO) {
        mutex.withLock {
            val directory = temp()
            try {
                val pack = readArchive(sourceFile(source, directory), directory); validatePackage(pack)
                ArchivePreview(pack.kind, pack.owner, if (pack.kind == ArchiveKind.LIBRARY) pack.tables.getJSONArray("pieces").length() else 0,
                    pack.files.size, pack.tables.getJSONArray("annotation_pages").length(), pack.hash)
            } finally { directory.deleteRecursively() }
        }
    }
    override suspend fun restore(member: String?, source: Uri, expectedHash: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val directory = temp()
            try { restoreFile(member, sourceFile(source, directory), expectedHash) }
            finally { directory.deleteRecursively() }
        }
    }
    internal suspend fun restoreFile(actor: String?, archive: File, expectedHash: String) = withContext(Dispatchers.IO) {
        val directory = temp(); val moved = mutableListOf<File>(); var committed = false
        try {
            val pack = readArchive(archive, directory)
            require(pack.hash == expectedHash) { "备份文件在确认后发生变化，请重新选择" }
            validatePackage(pack)
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) { database.withTransaction {
                val sql = database.openHelper.writableDatabase
                if (pack.kind == ArchiveKind.LIBRARY) {
                    val count = (ArchiveFormat.tables + "score_cache_entries").sumOf { table ->
                        sql.query("SELECT count(*) FROM `$table`").use { it.moveToFirst(); it.getLong(0) }
                    }
                    check(count == 0L) { "母库只能恢复到空库，现有数据不会覆盖" }
                    val scores = File(context.filesDir, "scores").apply { check(exists() || mkdirs()) }
                    val targets = pack.files.mapValues { (_, file) ->
                        File(scores, "${UUID.randomUUID()}.pdf").also { target ->
                            moved += target; check(file.renameTo(target)) { "恢复文件移动失败" }
                        }
                    }
                    val tables = JSONObject(pack.tables.toString())
                    ArchiveFormat.rows(tables.getJSONArray("score_pdf_versions")).forEach { it.put("managedFilePath", targets.getValue(it.getString("id")).path) }
                    insertLibrary(database, tables)
                } else {
                    check(actor != null && actor == pack.owner) { "只能恢复原成员本人的个人批注" }
                    member(actor)
                    val versions = database.resolutionDao().versions()
                    for (ref in ArchiveFormat.rows(pack.root.getJSONArray("versionReferences"))) {
                        require(versions.any { it.id == ref.getString("id") && it.fileHash.equals(ref.getString("sha256"), true) && it.pageCount == ref.getInt("pageCount") }) { "原谱版本缺失或不一致，请先恢复母库" }
                    }
                    for (page in ArchiveFormat.rows(pack.tables.getJSONArray("annotation_pages"))) {
                        val existing = ArchiveFormat.rows(ArchiveFormat.read(sql, "annotation_pages",
                            "WHERE versionId = ? AND pageIndex = ? AND ownerKey = ?",
                            arrayOf(page.getString("versionId"), page.getInt("pageIndex"), actor))).singleOrNull()
                        if (existing != null) {
                            require(ArchiveFormat.keys(page) == ArchiveFormat.keys(existing) && ArchiveFormat.keys(page).all {
                                val a = page.get(it); val b = existing.get(it)
                                if (a is Number && b is Number) a.toLong() == b.toLong() else a == b
                            }) { "个人批注有冲突，未覆盖任何现有批注" }
                        } else ArchiveFormat.insert(sql, "annotation_pages", JSONArray().put(page))
                    }
                }
            }; committed = true }
        } finally {
            if (!committed) moved.forEach { it.delete() }
            directory.deleteRecursively()
        }
    }
}
