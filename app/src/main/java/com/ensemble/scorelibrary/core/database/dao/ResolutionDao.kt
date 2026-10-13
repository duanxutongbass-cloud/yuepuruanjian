package com.ensemble.scorelibrary.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.ensemble.scorelibrary.core.database.entity.*
import com.ensemble.scorelibrary.domain.LibrarySnapshot

@Dao
abstract class ResolutionDao {
    @Query("SELECT * FROM pieces")
    abstract suspend fun pieces(): List<PieceEntity>

    @Query("SELECT * FROM movements")
    abstract suspend fun movements(): List<MovementEntity>

    @Query("SELECT * FROM part_definitions")
    abstract suspend fun definitions(): List<PartDefinitionEntity>

    @Query("SELECT * FROM piece_parts")
    abstract suspend fun parts(): List<PiecePartEntity>

    @Query("SELECT * FROM score_pdfs")
    abstract suspend fun pdfs(): List<ScorePdfEntity>

    @Query("SELECT * FROM score_pdf_versions")
    abstract suspend fun versions(): List<ScorePdfVersionEntity>

    @Query("SELECT * FROM part_score_bindings")
    abstract suspend fun bindings(): List<PartScoreBindingEntity>

    @Query("SELECT * FROM members")
    abstract suspend fun members(): List<MemberEntity>

    @Query("SELECT * FROM member_part_permissions")
    abstract suspend fun permissions(): List<MemberPartPermissionEntity>

    @Query("SELECT * FROM member_piece_part_permissions")
    abstract suspend fun extraPermissions(): List<MemberPiecePartPermissionEntity>

    @Query("SELECT * FROM piece_part_assignments")
    abstract suspend fun assignments(): List<PiecePartAssignmentEntity>

    @Query("SELECT * FROM concerts")
    abstract suspend fun concerts(): List<ConcertEntity>

    @Query("SELECT * FROM concert_items")
    abstract suspend fun items(): List<ConcertItemEntity>

    @Query("SELECT * FROM score_cache_entries")
    abstract suspend fun caches(): List<ScoreCacheEntryEntity>

    @Transaction
    open suspend fun snapshot(): LibrarySnapshot = LibrarySnapshot(
        pieces = pieces(),
        movements = movements(),
        definitions = definitions(),
        parts = parts(),
        pdfs = pdfs(),
        versions = versions(),
        bindings = bindings(),
        members = members(),
        permissions = permissions(),
        extraPermissions = extraPermissions(),
        assignments = assignments(),
        concerts = concerts(),
        items = items(),
        caches = caches(),
    )
}
