package com.ensemble.scorelibrary.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "annotation_pages", foreignKeys = [ForeignKey(entity = ScorePdfVersionEntity::class,
    parentColumns = ["id"], childColumns = ["versionId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("versionId"), Index(value = ["versionId", "pageIndex", "ownerKey"], unique = true)])
data class AnnotationPageEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val versionId: String, val pageIndex: Int, val ownerKey: String,
    val payload: String = "[]", val generation: Long = 0,
    val publishedRevisionId: String? = null,
)

@Entity(tableName = "annotation_revisions", foreignKeys = [ForeignKey(entity = AnnotationPageEntity::class,
    parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("documentId")])
data class AnnotationRevisionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(), val documentId: String,
    val payload: String, val createdBy: String, val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "annotation_concert_snapshots", foreignKeys = [ForeignKey(entity = ConcertEntity::class,
    parentColumns = ["id"], childColumns = ["concertId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["concertId"], unique = true)])
data class AnnotationConcertSnapshotEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(), val concertId: String,
    val payload: String, val manifest: String, val createdBy: String,
    val createdAt: Long = System.currentTimeMillis(),
)
