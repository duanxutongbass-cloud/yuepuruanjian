package com.ensemble.scorelibrary.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.sync.FolderPayload
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class FolderRetireTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private fun store()=LibraryFolderStore(context)
    private val member=UUID.randomUUID().toString()
    private val gone=UUID.randomUUID().toString()
    private val keep=UUID.randomUUID().toString()
    @Test fun lateEditsAndRestore() {
        val original=LibraryFolder(name="排练",pieces=setOf(gone,keep),coverPiece=gone)
        val image=LibraryFolder(name="上传封面",pieces=setOf(keep),image="opaque-original-image")
        store().edit(member,original);store().edit(member,image)
        store().edit("other-$member",original)
        store().retire(member,setOf(gone)) {}
        store().edit(member,original)
        val current=store().read(member)
        assertEquals(original.copy(pieces=setOf(keep),coverPiece=null),current.single { it.id==original.id })
        assertEquals(image,current.single { it.id==image.id })
        assertEquals(listOf(original),store().read("other-$member"))
        // A delayed cloud/backup candidate cannot reinsert the retired piece.
        val payload=FolderPayload.encode(listOf(original))
        assertTrue(store().replace(member,payload,store().fingerprint(member)) { it() })
        assertEquals(setOf(keep),store().read(member).single().pieces)
        assertNull(store().read(member).single().coverPiece)
    }
    @Test fun deniedAndDamaged() {
        val original=LibraryFolder(name="排练",pieces=setOf(gone),coverPiece=gone)
        store().edit(member,original)
        assertThrows(IllegalStateException::class.java) { store().retire(member,setOf(gone)) { error("changed") } }
        assertEquals(listOf(original),store().read(member))
        val p=context.getSharedPreferences("personal-library-folders",Context.MODE_PRIVATE)
        p.edit().putString("folders-$member","broken").commit()
        assertThrows(Exception::class.java) { store().retire(member,setOf(gone)) {} }
        assertEquals("broken",p.getString("folders-$member",null))
    }
}
