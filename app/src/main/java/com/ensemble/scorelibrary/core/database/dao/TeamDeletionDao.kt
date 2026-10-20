package com.ensemble.scorelibrary.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.ensemble.scorelibrary.core.database.entity.AnnotationConcertSnapshotEntity

/** Exact identities only; no migration or broad cache clear. Called in one Room transaction. */
@Dao
interface TeamDeletionDao {
    @Query("SELECT * FROM annotation_concert_snapshots") suspend fun snapshots(): List<AnnotationConcertSnapshotEntity>
    @Query("DELETE FROM annotation_concert_snapshots WHERE concertId IN (:ids)") suspend fun snapshots(ids: List<String>)
    @Query("DELETE FROM concert_items WHERE concertId IN (:ids)") suspend fun concertItems(ids: List<String>)
    @Query("DELETE FROM concerts WHERE id IN (:ids)") suspend fun concerts(ids: List<String>)
    @Query("DELETE FROM annotation_revisions WHERE documentId IN (SELECT id FROM annotation_pages WHERE versionId IN (:ids))") suspend fun revisions(ids: List<String>)
    @Query("DELETE FROM annotation_cloud WHERE versionId IN (:ids)") suspend fun cloud(ids: List<String>)
    @Query("DELETE FROM annotation_pages WHERE versionId IN (:ids)") suspend fun pages(ids: List<String>)
    @Query("DELETE FROM score_cache_entries WHERE scorePdfVersionId IN (:ids)") suspend fun caches(ids: List<String>)
    @Query("DELETE FROM part_score_bindings WHERE scorePdfId IN (:ids)") suspend fun bindings(ids: List<String>)
    @Query("DELETE FROM score_pdf_versions WHERE id IN (:ids)") suspend fun versions(ids: List<String>)
    @Query("DELETE FROM score_pdfs WHERE id IN (:ids)") suspend fun pdfs(ids: List<String>)
    @Query("DELETE FROM member_piece_part_permissions WHERE piecePartId IN (:ids)") suspend fun permissions(ids: List<String>)
    @Query("DELETE FROM piece_part_assignments WHERE piecePartId IN (:ids)") suspend fun assignments(ids: List<String>)
    @Query("DELETE FROM piece_parts WHERE id IN (:ids)") suspend fun parts(ids: List<String>)
    @Query("DELETE FROM concert_items WHERE pieceId IN (:ids)") suspend fun pieceItems(ids: List<String>)
    @Query("DELETE FROM movements WHERE pieceId IN (:ids)") suspend fun movements(ids: List<String>)
    @Query("DELETE FROM pieces WHERE id IN (:ids)") suspend fun pieces(ids: List<String>)
}
