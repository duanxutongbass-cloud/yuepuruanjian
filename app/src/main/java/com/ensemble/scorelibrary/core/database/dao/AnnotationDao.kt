package com.ensemble.scorelibrary.core.database.dao

import androidx.room.*
import com.ensemble.scorelibrary.core.database.entity.*

@Dao
interface AnnotationDao {
    @Query("SELECT * FROM annotation_pages") suspend fun pages(): List<AnnotationPageEntity>
    @Query("SELECT * FROM annotation_cloud") suspend fun cloudPages(): List<AnnotationCloudEntity>
    @Query("SELECT * FROM annotation_cloud WHERE id=:id") suspend fun cloud(id: String): AnnotationCloudEntity?
    @Upsert suspend fun putCloud(page: AnnotationCloudEntity)
    @Query("SELECT * FROM annotation_pages WHERE versionId = :version AND pageIndex = :page AND ownerKey = :owner")
    suspend fun page(version: String, page: Int, owner: String): AnnotationPageEntity?
    @Query("SELECT * FROM annotation_pages WHERE ownerKey = 'shared' AND versionId IN (:versions)")
    suspend fun sharedPages(versions: List<String>): List<AnnotationPageEntity>
    @Insert suspend fun insertPage(page: AnnotationPageEntity)
    @Update suspend fun updatePage(page: AnnotationPageEntity)
    @Insert suspend fun insertRevision(revision: AnnotationRevisionEntity)
    @Query("SELECT * FROM annotation_revisions WHERE id = :id")
    suspend fun revision(id: String): AnnotationRevisionEntity?
    @Query("SELECT * FROM annotation_concert_snapshots WHERE concertId = :concert")
    suspend fun snapshot(concert: String): AnnotationConcertSnapshotEntity?
    @Insert suspend fun insertSnapshot(snapshot: AnnotationConcertSnapshotEntity)
}
