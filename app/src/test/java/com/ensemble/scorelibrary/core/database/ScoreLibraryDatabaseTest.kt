package com.ensemble.scorelibrary.core.database

import android.content.Context
import androidx.room.Room
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.core.database.dao.LibraryDao
import com.ensemble.scorelibrary.core.database.entity.ConcertEntity
import com.ensemble.scorelibrary.core.database.entity.ConcertItemEntity
import com.ensemble.scorelibrary.core.database.entity.PartDefinitionEntity
import com.ensemble.scorelibrary.core.database.entity.PartScoreBindingEntity
import com.ensemble.scorelibrary.core.database.entity.PieceEntity
import com.ensemble.scorelibrary.core.database.entity.PiecePartEntity
import com.ensemble.scorelibrary.core.database.entity.ScorePdfEntity
import com.ensemble.scorelibrary.core.database.entity.ScorePdfVersionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScoreLibraryDatabaseTest {
    private lateinit var database: ScoreLibraryDatabase
    private lateinit var dao: LibraryDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ScoreLibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.libraryDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun scorePdfUsesActiveVersionAsSingleCurrentSource() {
        runBlocking {
            val scorePdf = ScorePdfEntity(title = "Violin I")
            dao.insertScorePdf(scorePdf)
            val v1 = version(scorePdf.id, 1)
            val v2 = version(scorePdf.id, 2)
            dao.insertScorePdfVersion(v1)
            dao.insertScorePdfVersion(v2)

            dao.updateScorePdf(scorePdf.copy(activeVersionId = v2.id))
            assertEquals(v2.id, dao.getScorePdf(scorePdf.id)?.activeVersionId)

            dao.updateScorePdf(scorePdf.copy(activeVersionId = v1.id))
            assertEquals(v1.id, dao.getScorePdf(scorePdf.id)?.activeVersionId)
        }
    }

    @Test
    fun scorePdfVersionNumberIsUniquePerScorePdf() {
        runBlocking {
            val scorePdf = ScorePdfEntity(title = "Cello")
            dao.insertScorePdf(scorePdf)
            dao.insertScorePdfVersion(version(scorePdf.id, 1))

            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking { dao.insertScorePdfVersion(version(scorePdf.id, 1)) }
            }
        }
    }

    @Test
    fun piecePartIsUniquePerPieceAndPartDefinition() {
        runBlocking {
            val piece = PieceEntity(title = "天空之城")
            val part = PartDefinitionEntity(name = "小提琴 I")
            dao.insertPiece(piece)
            dao.insertPartDefinition(part)
            dao.insertPiecePart(PiecePartEntity(pieceId = piece.id, partDefinitionId = part.id))

            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking { dao.insertPiecePart(PiecePartEntity(pieceId = piece.id, partDefinitionId = part.id)) }
            }
        }
    }

    @Test
    fun concertSortOrderIsUniqueInsideConcert() {
        runBlocking {
            val concert = ConcertEntity(title = "春季音乐会")
            val firstPiece = PieceEntity(title = "A")
            val secondPiece = PieceEntity(title = "B")
            dao.insertConcert(concert)
            dao.insertPiece(firstPiece)
            dao.insertPiece(secondPiece)
            dao.insertConcertItem(ConcertItemEntity(concertId = concert.id, pieceId = firstPiece.id, sortOrder = 1))

            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking {
                    dao.insertConcertItem(ConcertItemEntity(concertId = concert.id, pieceId = secondPiece.id, sortOrder = 1))
                }
            }
        }
    }

    @Test
    fun partScoreBindingRequiresExistingPiecePartAndScorePdf() {
        runBlocking {
            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking {
                    dao.insertPartScoreBinding(
                        PartScoreBindingEntity(
                            piecePartId = "missing-piece-part",
                            scorePdfId = "missing-score-pdf",
                            startPage = 1,
                            endPage = 2
                        )
                    )
                }
            }
        }
    }

    private fun version(scorePdfId: String, versionNumber: Int): ScorePdfVersionEntity =
        ScorePdfVersionEntity(
            scorePdfId = scorePdfId,
            versionNumber = versionNumber,
            managedFilePath = "managed/$scorePdfId/v$versionNumber.pdf",
            originalUri = "content://score/$versionNumber",
            fileHash = "sha256-$versionNumber",
            fileSizeBytes = 1024L,
            pageCount = 8
        )
}
