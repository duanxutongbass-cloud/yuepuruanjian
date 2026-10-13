package com.ensemble.scorelibrary.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.ensemble.scorelibrary.core.database.converter.EnumConverters
import com.ensemble.scorelibrary.core.database.dao.LibraryDao
import com.ensemble.scorelibrary.core.database.entity.ConcertEntity
import com.ensemble.scorelibrary.core.database.entity.ConcertItemEntity
import com.ensemble.scorelibrary.core.database.entity.MemberEntity
import com.ensemble.scorelibrary.core.database.entity.MemberPartPermissionEntity
import com.ensemble.scorelibrary.core.database.entity.MemberPiecePartPermissionEntity
import com.ensemble.scorelibrary.core.database.entity.MovementEntity
import com.ensemble.scorelibrary.core.database.entity.PartDefinitionEntity
import com.ensemble.scorelibrary.core.database.entity.PartScoreBindingEntity
import com.ensemble.scorelibrary.core.database.entity.PieceEntity
import com.ensemble.scorelibrary.core.database.entity.PiecePartAssignmentEntity
import com.ensemble.scorelibrary.core.database.entity.PiecePartEntity
import com.ensemble.scorelibrary.core.database.entity.ScoreCacheEntryEntity
import com.ensemble.scorelibrary.core.database.entity.ScorePdfEntity
import com.ensemble.scorelibrary.core.database.entity.ScorePdfVersionEntity

@Database(
    entities = [
        PieceEntity::class,
        MovementEntity::class,
        PartDefinitionEntity::class,
        PiecePartEntity::class,
        ScorePdfEntity::class,
        ScorePdfVersionEntity::class,
        PartScoreBindingEntity::class,
        MemberEntity::class,
        MemberPartPermissionEntity::class,
        MemberPiecePartPermissionEntity::class,
        PiecePartAssignmentEntity::class,
        ConcertEntity::class,
        ConcertItemEntity::class,
        ScoreCacheEntryEntity::class,
        com.ensemble.scorelibrary.core.database.entity.AnnotationPageEntity::class,
        com.ensemble.scorelibrary.core.database.entity.AnnotationRevisionEntity::class,
        com.ensemble.scorelibrary.core.database.entity.AnnotationConcertSnapshotEntity::class
    ],
    version = 3,
    exportSchema = true
)
@TypeConverters(EnumConverters::class)
abstract class ScoreLibraryDatabase : RoomDatabase() {
    companion object {
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS annotation_pages (id TEXT NOT NULL PRIMARY KEY, versionId TEXT NOT NULL, pageIndex INTEGER NOT NULL, ownerKey TEXT NOT NULL, payload TEXT NOT NULL, generation INTEGER NOT NULL, publishedRevisionId TEXT, FOREIGN KEY(versionId) REFERENCES score_pdf_versions(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_annotation_pages_versionId ON annotation_pages(versionId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_annotation_pages_versionId_pageIndex_ownerKey ON annotation_pages(versionId, pageIndex, ownerKey)")
                db.execSQL("CREATE TABLE IF NOT EXISTS annotation_revisions (id TEXT NOT NULL PRIMARY KEY, documentId TEXT NOT NULL, payload TEXT NOT NULL, createdBy TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(documentId) REFERENCES annotation_pages(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_annotation_revisions_documentId ON annotation_revisions(documentId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS annotation_concert_snapshots (id TEXT NOT NULL PRIMARY KEY, concertId TEXT NOT NULL, payload TEXT NOT NULL, manifest TEXT NOT NULL, createdBy TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(concertId) REFERENCES concerts(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_annotation_concert_snapshots_concertId ON annotation_concert_snapshots(concertId)")
            }
        }
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE concert_items ADD COLUMN isEnabled INTEGER NOT NULL DEFAULT 1")
            }
        }
    }
    abstract fun libraryDao(): LibraryDao
    abstract fun annotationDao(): com.ensemble.scorelibrary.core.database.dao.AnnotationDao
    abstract fun resolutionDao(): com.ensemble.scorelibrary.core.database.dao.ResolutionDao
}
