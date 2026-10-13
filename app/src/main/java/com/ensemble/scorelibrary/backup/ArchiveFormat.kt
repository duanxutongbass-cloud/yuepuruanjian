package com.ensemble.scorelibrary.backup

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

enum class ArchiveKind { LIBRARY, PERSONAL }
data class ArchivePreview(val kind: ArchiveKind, val owner: String, val pieces: Int,
    val files: Int, val annotations: Int, val hash: String)

internal object ArchiveFormat {
    const val NAME = "ensemble-score-library"
    const val JSON_LIMIT = 16 * 1024 * 1024
    const val PDF_LIMIT = 256L * 1024 * 1024
    const val TOTAL_LIMIT = 2L * 1024 * 1024 * 1024
    val tables = listOf("pieces", "part_definitions", "members", "movements", "piece_parts",
        "score_pdfs", "score_pdf_versions", "part_score_bindings", "member_part_permissions",
        "member_piece_part_permissions", "piece_part_assignments", "concerts", "concert_items",
        "annotation_pages", "annotation_revisions", "annotation_concert_snapshots")
    fun rows(array: JSONArray): List<JSONObject> = List(array.length()) { array.getJSONObject(it) }
    fun keys(value: JSONObject) = value.keys().asSequence().toSet()
    fun depthCheck(text: String) {
        var depth = 0; var quoted = false; var escaped = false
        for (char in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 32) { "备份 JSON 嵌套过深" } }
                '}', ']' -> { depth--; require(depth >= 0) }
            }
        }
    }
    fun columns(db: SupportSQLiteDatabase, table: String): Map<String, Pair<String, Boolean>> {
        require(table in tables)
        return db.query("PRAGMA table_info(`$table`)").use { cursor -> buildMap {
            while (cursor.moveToNext()) put(cursor.getString(1), cursor.getString(2) to (cursor.getInt(3) != 0))
        } }
    }
    fun read(db: SupportSQLiteDatabase, table: String, where: String = "", args: Array<Any> = emptyArray()): JSONArray {
        require(table in tables)
        var bytes = 0L
        return db.query("SELECT * FROM `$table` $where ORDER BY id", args).use { cursor -> JSONArray().apply {
            while (cursor.moveToNext()) {
                val row = JSONObject().apply {
                cursor.columnNames.forEachIndexed { index, name -> put(name, when {
                    cursor.isNull(index) -> JSONObject.NULL
                    cursor.getType(index) == android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                    else -> cursor.getString(index)
                }) }
                }
                bytes += row.toString().toByteArray().size
                require(bytes <= JSON_LIMIT && length() < 100_000) { "备份元数据过大" }
                put(row)
            }
        } }
    }
    fun insert(db: SupportSQLiteDatabase, table: String, rows: JSONArray) {
        val columns = columns(db, table)
        for (row in ArchiveFormat.rows(rows)) {
            val values = values(row, columns)
            val names = columns.keys.joinToString(",") { "`$it`" }
            db.execSQL("INSERT INTO `$table` ($names) VALUES (${columns.keys.joinToString(",") { "?" }})", values)
        }
    }
    fun values(row: JSONObject, columns: Map<String, Pair<String, Boolean>>): Array<Any?> {
            require(keys(row) == columns.keys) { "备份字段不匹配" }
            return columns.map { (name, spec) ->
                val value = row.get(name)
                if (value == JSONObject.NULL) { require(!spec.second); null }
                else when (spec.first) {
                    "INTEGER" -> {
                        require(value is Int || value is Long) { "整数格式无效：$name" }
                        if (name.startsWith("is")) require((value as Number).toLong() in 0..1)
                        value
                    }
                    "TEXT" -> {
                        require(value is String)
                        if (name == "id" || name.endsWith("Id"))
                            require(value.isNotBlank() && value.length <= 200 && value.none { it in ":|#\u0000" }) { "备份身份字段无效" }
                        value
                    }
                    else -> error("不支持的备份字段类型")
                }
            }.toTypedArray<Any?>()
    }
    fun entry(version: String) = "files/${digest(version.toByteArray())}.pdf"
    fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    suspend fun copy(input: InputStream, output: OutputStream?, limit: Long): Pair<Long, String> {
        var size = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            size += count
            require(size <= limit) { "备份超过安全大小上限" }
            digest.update(buffer, 0, count)
            output?.write(buffer, 0, count)
        }
        return size to hex(digest.digest())
    }
    suspend fun hash(file: java.io.File) = file.inputStream().use { copy(it, null, TOTAL_LIMIT).second }
}
