package com.ensemble.scorelibrary.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ensemble.scorelibrary.core.database.entity.ConcertEntity
import com.ensemble.scorelibrary.core.database.entity.ConcertItemEntity
import com.ensemble.scorelibrary.core.database.entity.MemberEntity
import com.ensemble.scorelibrary.core.database.entity.MovementEntity
import com.ensemble.scorelibrary.core.database.entity.PartDefinitionEntity
import com.ensemble.scorelibrary.core.database.entity.PartScoreBindingEntity
import com.ensemble.scorelibrary.core.database.entity.PieceEntity
import com.ensemble.scorelibrary.core.database.entity.PiecePartEntity
import com.ensemble.scorelibrary.core.database.entity.ScorePdfEntity
import com.ensemble.scorelibrary.core.database.entity.ScorePdfVersionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Update suspend fun updatePiece(entity: PieceEntity)
    @Update suspend fun updateMovement(entity: MovementEntity)
    @Update suspend fun updatePartDefinition(entity: PartDefinitionEntity)
    @Update suspend fun updatePiecePart(entity: PiecePartEntity)
    @Update suspend fun updatePartScoreBinding(entity: PartScoreBindingEntity)
    @Update suspend fun updateConcert(entity: ConcertEntity)
    @Update suspend fun updateConcertItem(entity: ConcertItemEntity)
    @Update suspend fun updateExtraPermission(entity: com.ensemble.scorelibrary.core.database.entity.MemberPiecePartPermissionEntity)
    @Update suspend fun updateAssignment(entity: com.ensemble.scorelibrary.core.database.entity.PiecePartAssignmentEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPermission(entity: com.ensemble.scorelibrary.core.database.entity.MemberPartPermissionEntity)
    @Update
    suspend fun updatePermission(entity: com.ensemble.scorelibrary.core.database.entity.MemberPartPermissionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExtraPermission(entity: com.ensemble.scorelibrary.core.database.entity.MemberPiecePartPermissionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAssignment(entity: com.ensemble.scorelibrary.core.database.entity.PiecePartAssignmentEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCache(entity: com.ensemble.scorelibrary.core.database.entity.ScoreCacheEntryEntity)

    @Update
    suspend fun updateMember(entity: MemberEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPiece(entity: PieceEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMovement(entity: MovementEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPartDefinition(entity: PartDefinitionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPiecePart(entity: PiecePartEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertScorePdf(entity: ScorePdfEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertScorePdfVersion(entity: ScorePdfVersionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPartScoreBinding(entity: PartScoreBindingEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMember(entity: MemberEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConcert(entity: ConcertEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConcertItem(entity: ConcertItemEntity)

    @Update
    suspend fun updateScorePdf(entity: ScorePdfEntity)

    @Query("SELECT * FROM pieces WHERE isArchived = 0 ORDER BY title")
    fun observeActivePieces(): Flow<List<PieceEntity>>

    @Query("SELECT * FROM score_pdfs WHERE id = :id")
    suspend fun getScorePdf(id: String): ScorePdfEntity?

    @Query("SELECT * FROM score_pdf_versions WHERE scorePdfId = :scorePdfId ORDER BY versionNumber")
    suspend fun getScorePdfVersions(scorePdfId: String): List<ScorePdfVersionEntity>
}
