package com.ensemble.scorelibrary.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ensemble.scorelibrary.core.model.CacheStatus
import com.ensemble.scorelibrary.core.model.ConcertStatus
import com.ensemble.scorelibrary.core.model.MemberRole
import java.util.UUID

@Entity(tableName = "pieces")
data class PieceEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val composer: String? = null,
    val arranger: String? = null,
    val notes: String? = null,
    val isArchived: Boolean = false,
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "movements",
    foreignKeys = [
        ForeignKey(
            entity = PieceEntity::class,
            parentColumns = ["id"],
            childColumns = ["pieceId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index("pieceId"),
        Index(value = ["pieceId", "sortOrder"], unique = true)
    ]
)
data class MovementEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val pieceId: String,
    val title: String,
    val sortOrder: Int,
    val isArchived: Boolean = false
)

@Entity(
    tableName = "part_definitions",
    indices = [Index(value = ["name"], unique = true)]
)
data class PartDefinitionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val family: String? = null,
    val sortOrder: Int = 0,
    val isArchived: Boolean = false
)

@Entity(
    tableName = "piece_parts",
    foreignKeys = [
        ForeignKey(
            entity = PieceEntity::class,
            parentColumns = ["id"],
            childColumns = ["pieceId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = PartDefinitionEntity::class,
            parentColumns = ["id"],
            childColumns = ["partDefinitionId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index("pieceId"),
        Index("partDefinitionId"),
        Index(value = ["pieceId", "partDefinitionId"], unique = true)
    ]
)
data class PiecePartEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val pieceId: String,
    val partDefinitionId: String,
    val displayName: String? = null,
    val isArchived: Boolean = false
)

@Entity(
    tableName = "score_pdfs",
    foreignKeys = [
        ForeignKey(
            entity = ScorePdfVersionEntity::class,
            parentColumns = ["id"],
            childColumns = ["activeVersionId"],
            onDelete = ForeignKey.SET_NULL,
            deferred = true
        )
    ],
    indices = [Index("activeVersionId")]
)
data class ScorePdfEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val activeVersionId: String? = null,
    val isArchived: Boolean = false,
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "score_pdf_versions",
    foreignKeys = [
        ForeignKey(
            entity = ScorePdfEntity::class,
            parentColumns = ["id"],
            childColumns = ["scorePdfId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index("scorePdfId"),
        Index(value = ["scorePdfId", "versionNumber"], unique = true),
        Index(value = ["fileHash"])
    ]
)
data class ScorePdfVersionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val scorePdfId: String,
    val versionNumber: Int,
    val managedFilePath: String,
    val originalUri: String,
    val fileHash: String,
    val fileSizeBytes: Long,
    val pageCount: Int,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val createdByMemberId: String? = null
)

@Entity(
    tableName = "part_score_bindings",
    foreignKeys = [
        ForeignKey(
            entity = PiecePartEntity::class,
            parentColumns = ["id"],
            childColumns = ["piecePartId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = MovementEntity::class,
            parentColumns = ["id"],
            childColumns = ["movementId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ScorePdfEntity::class,
            parentColumns = ["id"],
            childColumns = ["scorePdfId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index("piecePartId"),
        Index("movementId"),
        Index("scorePdfId"),
        Index(value = ["piecePartId", "movementId"], unique = true)
    ]
)
data class PartScoreBindingEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val piecePartId: String,
    val movementId: String? = null,
    val scorePdfId: String,
    val startPage: Int,
    val endPage: Int,
    val isArchived: Boolean = false
)

@Entity(
    tableName = "members",
    foreignKeys = [
        ForeignKey(
            entity = PartDefinitionEntity::class,
            parentColumns = ["id"],
            childColumns = ["defaultPartDefinitionId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("defaultPartDefinitionId"),
        Index(value = ["displayName"], unique = true)
    ]
)
data class MemberEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val role: MemberRole = MemberRole.MEMBER,
    val defaultPartDefinitionId: String? = null,
    val isArchived: Boolean = false
)

@Entity(
    tableName = "member_part_permissions",
    foreignKeys = [
        ForeignKey(entity = MemberEntity::class, parentColumns = ["id"], childColumns = ["memberId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = PartDefinitionEntity::class, parentColumns = ["id"], childColumns = ["partDefinitionId"], onDelete = ForeignKey.RESTRICT)
    ],
    indices = [
        Index("memberId"),
        Index("partDefinitionId"),
        Index(value = ["memberId", "partDefinitionId"], unique = true)
    ]
)
data class MemberPartPermissionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val memberId: String,
    val partDefinitionId: String,
    val isArchived: Boolean = false
)

@Entity(
    tableName = "member_piece_part_permissions",
    foreignKeys = [
        ForeignKey(entity = MemberEntity::class, parentColumns = ["id"], childColumns = ["memberId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = PiecePartEntity::class, parentColumns = ["id"], childColumns = ["piecePartId"], onDelete = ForeignKey.RESTRICT)
    ],
    indices = [
        Index("memberId"),
        Index("piecePartId"),
        Index(value = ["memberId", "piecePartId"], unique = true)
    ]
)
data class MemberPiecePartPermissionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val memberId: String,
    val piecePartId: String,
    val isArchived: Boolean = false
)

@Entity(
    tableName = "piece_part_assignments",
    foreignKeys = [
        ForeignKey(entity = MemberEntity::class, parentColumns = ["id"], childColumns = ["memberId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = PiecePartEntity::class, parentColumns = ["id"], childColumns = ["piecePartId"], onDelete = ForeignKey.RESTRICT)
    ],
    indices = [
        Index("memberId"),
        Index("piecePartId"),
        Index(value = ["memberId", "piecePartId"], unique = true)
    ]
)
data class PiecePartAssignmentEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val memberId: String,
    val piecePartId: String,
    val isArchived: Boolean = false
)

@Entity(tableName = "concerts")
data class ConcertEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val performanceAtEpochMillis: Long? = null,
    val status: ConcertStatus = ConcertStatus.DRAFT,
    val isArchived: Boolean = false
)

@Entity(
    tableName = "concert_items",
    foreignKeys = [
        ForeignKey(entity = ConcertEntity::class, parentColumns = ["id"], childColumns = ["concertId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = PieceEntity::class, parentColumns = ["id"], childColumns = ["pieceId"], onDelete = ForeignKey.RESTRICT)
    ],
    indices = [
        Index("concertId"),
        Index("pieceId"),
        Index(value = ["concertId", "sortOrder"], unique = true)
    ]
)
data class ConcertItemEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val concertId: String,
    val pieceId: String,
    val sortOrder: Int,
    @androidx.room.ColumnInfo(defaultValue = "1") val isEnabled: Boolean = true,
    val isArchived: Boolean = false
)

@Entity(
    tableName = "score_cache_entries",
    foreignKeys = [
        ForeignKey(entity = ScorePdfVersionEntity::class, parentColumns = ["id"], childColumns = ["scorePdfVersionId"], onDelete = ForeignKey.RESTRICT)
    ],
    indices = [
        Index("scorePdfVersionId"),
        Index(value = ["scorePdfVersionId", "memberId"], unique = true)
    ]
)
data class ScoreCacheEntryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val scorePdfVersionId: String,
    val memberId: String? = null,
    val status: CacheStatus = CacheStatus.NOT_CACHED,
    val cachedFilePath: String? = null,
    val cachedAtEpochMillis: Long? = null,
    val errorMessage: String? = null
)
