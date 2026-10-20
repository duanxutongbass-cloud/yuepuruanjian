package com.ensemble.scorelibrary.ui

import android.content.Context
import com.ensemble.scorelibrary.backup.ArchiveFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import com.ensemble.scorelibrary.sync.FolderPayload

internal data class FolderLocal(val fingerprint:String,val payload:String) {
    val sha256 get()=FolderPayload.hash(payload)
}
data class FolderEditBaseline(val id:String,val before:LibraryFolder?)

/** Same preference keys as 0.9.9; backup and editing share one compare-and-save boundary. */
@Singleton
class LibraryFolderStore @Inject constructor(@param:ApplicationContext context:Context) {
    private val preferences=context.getSharedPreferences("personal-library-folders",Context.MODE_PRIVATE)
    private val mutableChanges=MutableSharedFlow<String>(extraBufferCapacity=32,onBufferOverflow=BufferOverflow.DROP_OLDEST)
    internal val changes=mutableChanges.asSharedFlow()
    private fun key(member:String)="folders-$member"
    private fun raw(member:String)=preferences.getString(key(member),"[]")!!
    private fun retired(member:String):Set<String> {
        val rows=org.json.JSONArray(preferences.getString("retired-$member","[]")!!)
        require(rows.length()<=500_000)
        return (0 until rows.length()).map { rows.get(it) as String }.also { ids ->
            require(ids.distinct().size==ids.size && ids.all { java.util.UUID.fromString(it).toString()==it })
        }.toSet()
    }
    internal fun prune(folders:List<LibraryFolder>,pieces:Set<String>)=folders.map { f ->
        f.copy(pieces=f.pieces-pieces,coverPiece=f.coverPiece?.takeUnless { it in pieces })
    }
    @Synchronized fun read(member:String)=prune(FolderCodec.decode(raw(member)),retired(member))
    /** Authoritative globally disappeared piece IDs only, never the local grant projection. */
    @Synchronized internal fun retire(member:String,pieces:Set<String>,guard:()->Unit) {
        require(pieces.all { java.util.UUID.fromString(it).toString()==it });guard()
        val gone=retired(member)+pieces;require(gone.size<=500_000)
        val before=raw(member)
        val after=FolderCodec.encode(prune(FolderCodec.decode(before),gone))
        val previous=preferences.getString("retired-$member",null)
        guard()
        if(!preferences.edit().putString("retired-$member",org.json.JSONArray(gone.sorted()).toString())
                .putString(key(member),after).commit()) {
            preferences.edit().putString("retired-$member",previous).putString(key(member),before).commit()
            error("文件夹删除记录未能保存，不能确认清理完成")
        }
        mutableChanges.tryEmit(member)
    }
    @Synchronized internal fun sanitize(member:String,payload:String)=FolderPayload.encode(prune(FolderPayload.decode(payload),retired(member)))
    @Synchronized fun fingerprint(member:String):String {
        val value=raw(member);FolderCodec.decode(value)
        return ArchiveFormat.digest(value.toByteArray(Charsets.UTF_8))
    }
    @Synchronized fun edit(member:String,folder:LibraryFolder?,remove:String?=null,baseline:FolderEditBaseline?=null) {
        val current=read(member)
        if(baseline!=null)check(baseline.id==(folder?.id ?: remove) && current.singleOrNull { it.id==baseline.id }==baseline.before) {
            "编辑期间文件夹已变化，本次修改未覆盖新内容；请重新打开文件夹核对"
        }
        write(member,current.filterNot { it.id==(folder?.id ?: remove) }+listOfNotNull(folder))
    }
    @Synchronized internal fun snapshot(member:String):FolderLocal {
        val value=raw(member)
        return FolderLocal(ArchiveFormat.digest(value.toByteArray(Charsets.UTF_8)),FolderPayload.encode(read(member)))
    }
    @Synchronized internal fun replace(member:String,incoming:String,expected:String,commit:((()->Unit)->Unit)):Boolean {
        if(fingerprint(member)!=expected)return false
        val folders=FolderPayload.decode(incoming)
        commit { write(member,folders) }
        return true
    }
    private fun write(member:String,folders:List<LibraryFolder>) {
        val before=raw(member)
        val encoded=FolderCodec.encode(prune(folders,retired(member)))
        if(!preferences.edit().putString(key(member),encoded).commit()) {
            // SharedPreferences can change its memory view even when the disk write fails.
            preferences.edit().putString(key(member),before).commit()
            error("文件夹保存失败，不能确认恢复完成")
        }
        mutableChanges.tryEmit(member)
    }
    @Synchronized fun merge(member:String,incoming:List<LibraryFolder>,expected:String,guard:()->Unit) {
        check(fingerprint(member)==expected) { "预览后文件夹已变化，请重新选择备份并预览" }
        val current=read(member);val byId=current.associateBy { it.id }
        check(incoming.none { byId[it.id]!=null && byId[it.id]!=it }) { "同一文件夹记录存在冲突，已保留本机内容；请先核对" }
        val merged=current+incoming.filter { it.id !in byId }
        FolderCodec.encode(merged)
        guard()
        write(member,merged)
    }
}
