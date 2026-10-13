package com.ensemble.scorelibrary.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.core.database.entity.*
import com.ensemble.scorelibrary.data.RoomPresentationRepository
import com.ensemble.scorelibrary.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PresentationRepositoryTest {
    @Test fun roomChangesRevokeVisiblePartInObserver() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ScoreLibraryDatabase::class.java).build()
        try { runBlocking {
            val dao = db.libraryDao()
            dao.insertPiece(PieceEntity(id = "p", title = "Piece"))
            dao.insertPartDefinition(PartDefinitionEntity(id = "d", name = "Part"))
            dao.insertPiecePart(PiecePartEntity(id = "part", pieceId = "p", partDefinitionId = "d"))
            dao.insertMember(MemberEntity(id = "m", displayName = "Member"))
            val permission = MemberPartPermissionEntity(memberId = "m", partDefinitionId = "d")
            dao.insertPermission(permission)
            val events = Channel<DomainResult<Presentation>>(Channel.UNLIMITED)
            val observer = launch(Dispatchers.Default) {
                RoomPresentationRepository(db).observe("m", ScreenRequest.Piece("p")).collect { events.send(it) }
            }
            try {
                fun parts(event: DomainResult<Presentation>): List<VisiblePart> {
                    val presentation = (event as DomainResult.Success).value
                    return ((presentation.content as DomainResult.Success).value as ScreenContent.Piece).parts
                }
                assertEquals(listOf("part"), parts(withTimeout(10_000) { events.receive() }).map { it.id })
                dao.updatePermission(permission.copy(isArchived = true))
                withTimeout(10_000) {
                    while (parts(events.receive()).isNotEmpty()) { /* Drain any initial invalidation queued before the write. */ }
                }
            } finally { observer.cancelAndJoin(); events.close() }
        } } finally { db.close() }
    }
}
