package com.ensemble.scorelibrary.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import com.ensemble.scorelibrary.core.database.entity.*
import com.ensemble.scorelibrary.domain.*
import com.ensemble.scorelibrary.pdf.*
import kotlinx.coroutines.flow.flowOf
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PreviewPartChoiceTest {
    @get:Rule val compose=createComposeRule()
    private val store=ViewModelStore()
    @After fun close(){store.clear()}
    @Test fun multiPartLibraryOffersExistingAuthorizedPartPickerWithoutGuessingAPart() {
        val data=LibrarySnapshot(pieces=listOf(PieceEntity(id="p",title="Synthetic")),
            definitions=listOf(PartDefinitionEntity(id="d1",name="I"),PartDefinitionEntity(id="d2",name="II")),
            parts=listOf(PiecePartEntity(id="a",pieceId="p",partDefinitionId="d1"),PiecePartEntity(id="b",pieceId="p",partDefinitionId="d2")),
            members=listOf(MemberEntity(id="m",displayName="Synthetic")),
            extraPermissions=listOf(MemberPiecePartPermissionEntity(memberId="m",piecePartId="a"),MemberPiecePartPermissionEntity(memberId="m",piecePartId="b")))
        val repository=object:PresentationRepository {
            override fun observe(memberId:String?,request:ScreenRequest)=flowOf(ScoreResolver(data).presentation(memberId,request))
        }
        val engine=object:PdfEngine {
            override suspend fun inspect(segments:List<ResolvedScoreSegment>):List<PageSize> = error("Must choose an authorized part first")
            override suspend fun render(page:PdfPageRef,width:Int,height:Int,mode:FitMode,scroll:Float):PdfFrame = error("Must not guess a PDF")
        }
        val vm=ScorePreviewViewModel(repository,engine).also { store.put("preview",it) }
        var choose=false
        compose.setContent { MaterialTheme { ScorePreviewPane("m","p",vm,{_,_->fail("No part selected")},{choose=true}) } }
        compose.onNodeWithTag("preview-select-part").assertIsEnabled().performClick()
        assertTrue(choose)
        compose.onNodeWithTag("preview-open-reader").assertIsNotEnabled()
        compose.onNodeWithText("声部分配不完整，请联系管理员").assertDoesNotExist()
    }
}
