package com.ensemble.scorelibrary.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ensemble.scorelibrary.domain.PieceSummary
import com.ensemble.scorelibrary.domain.PresentationRepository
import com.ensemble.scorelibrary.pdf.PdfEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.inject.Inject

data class LibraryFolder(val id: String = UUID.randomUUID().toString(), val name: String,
    val pieces: Set<String> = emptySet(), val coverPiece: String? = null, val image: String? = null)

internal object FolderCodec {
    fun encode(folders: List<LibraryFolder>): String = JSONArray().apply {
        require(folders.size <= 100) { "最多保留 100 个文件夹，请先整理后重试" }
        folders.forEach { f ->
            require(f.name.isNotBlank() && f.name.length <= 100 && f.pieces.size <= 10000)
            put(JSONObject().put("id", f.id).put("name", f.name).put("pieces", JSONArray(f.pieces.toList()))
                .put("coverPiece", f.coverPiece ?: JSONObject.NULL).put("image", f.image ?: JSONObject.NULL))
        }
    }.toString().also { require(it.length <= 8 * 1024 * 1024) { "文件夹封面过多，请减少图片" } }
    fun decode(value: String): List<LibraryFolder> {
        require(value.length <= 8 * 1024 * 1024)
        val array = JSONArray(value)
        require(array.length() <= 100)
        return (0 until array.length()).map { i -> val row = array.getJSONObject(i)
            val pieces = row.getJSONArray("pieces")
            require(pieces.length() <= 10000)
            LibraryFolder(row.getString("id"), row.getString("name"),
                (0 until pieces.length()).map { pieces.getString(it) }.toSet(),
                if(row.isNull("coverPiece")) null else row.getString("coverPiece"),
                if(row.isNull("image")) null else row.getString("image"))
        }.also { require(it.map { f -> f.id }.toSet().size == it.size); encode(it) }
    }
}

data class FolderState(val member: String? = null, val folders: List<LibraryFolder> = emptyList(),
    val busy: Boolean = false, val error: String? = null)
val LocalFolders = staticCompositionLocalOf<LibraryFolderViewModel?> { null }

@HiltViewModel
class LibraryFolderViewModel @Inject constructor(@param:ApplicationContext private val context: Context,
    private val repository: PresentationRepository, private val engine: PdfEngine,
    private val folders:LibraryFolderStore=LibraryFolderStore(context)) : ViewModel() {
    private val mutable = MutableStateFlow(FolderState())
    private var generation=0
    val state = mutable.asStateFlow()
    init { viewModelScope.launch { folders.changes.collect { member -> if(mutable.value.member==member)load(member) } } }
    fun preview() = ScorePreviewViewModel(repository, engine)
    fun bind(member: String?) {
        if(member == mutable.value.member) return
        load(member)
    }
    fun reload(member:String) { if(member==mutable.value.member)load(member) }
    private fun load(member:String?) {
        val token=++generation
        mutable.value = FolderState(member, busy = member != null)
        if(member != null) viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { folders.read(member) }
                if(mutable.value.member == member && generation==token) mutable.value = FolderState(member, loaded)
            } catch (_: Exception) { if(mutable.value.member == member && generation==token) mutable.value = FolderState(member, error="文件夹读取失败，原记录已保留") }
        }
    }
    fun save(member: String, folder: LibraryFolder?, remove: String? = null,baseline:FolderEditBaseline?=null) {
        val before = mutable.value
        if(before.member != member || before.busy || before.error == "文件夹读取失败，原记录已保留") return
        mutable.value = before.copy(busy=true,error=null)
        val token=++generation
        viewModelScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) { folders.edit(member,folder,remove,baseline);folders.read(member) }
                if(mutable.value.member == member && generation==token) mutable.value = FolderState(member, updated)
            } catch (e: Exception) {
                if(mutable.value.member == member) {
                    val message=e.message ?: "文件夹保存失败"
                    val actual=withContext(Dispatchers.IO) { runCatching { folders.read(member) }.getOrDefault(before.folders) }
                    if(mutable.value.member==member) {
                        if(generation==token)mutable.value=FolderState(member,actual,error=message)
                        else mutable.value=mutable.value.copy(error=message)
                    }
                }
            }
        }
    }
    suspend fun image(uri: Uri): String = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytesLimited(20 * 1024 * 1024) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "请选择可读取的图片" }
        val options = BitmapFactory.Options().apply {
            var sample=1
            while(bounds.outWidth/sample > 512 || bounds.outHeight/sample > 512) sample*=2
            inSampleSize=sample
        }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size,options))
        try { val output=ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG,100,output)
            require(output.size() <= 512 * 1024) { "封面图片过大，请选择较简单的图片" }
            Base64.encodeToString(output.toByteArray(),Base64.NO_WRAP)
        } finally { bitmap.recycle() }
    }
}
private fun java.io.InputStream.readBytesLimited(max: Int): ByteArray {
    val output=ByteArrayOutputStream(); val buffer=ByteArray(8192)
    while(true) { val count=read(buffer); if(count < 0) break
        require(output.size()+count <= max) { "图片不能超过 20 MB" }; output.write(buffer,0,count) }
    return output.toByteArray()
}

@Composable
internal fun FolderBrowser(member: String, pieces: List<PieceSummary>, selected: String?,
    select: (String?) -> Unit, model: LibraryFolderViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(member) { model.bind(member) }
    val folders = state.takeIf { it.member == member }?.folders.orEmpty()
    LaunchedEffect(folders, state.busy, selected) {
        if(!state.busy && selected != null && folders.none { it.id == selected }) select(null)
    }
    var editing by remember(member) { mutableStateOf<LibraryFolder?>(null) }
    LazyRow(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        item { FilterChip(selected==null,{select(null)},label={Text("全部曲谱")}) }
        item { com.ensemble.scorelibrary.backup.FolderBackupEntry(member,model,!state.busy) }
        item { com.ensemble.scorelibrary.sync.FolderSyncEntry(member) }
        items(folders,key={it.id}) { folder ->
            ElevatedCard(onClick={select(folder.id)},modifier=Modifier.padding(6.dp).width(112.dp).testTag("folder-${folder.id}")) {
                FolderCover(member,folder,model)
                Text(folder.name,modifier=Modifier.padding(horizontal=8.dp),maxLines=1,style=MaterialTheme.typography.labelLarge)
                Text("${folder.pieces.count { id -> pieces.any { it.id==id } }} 首" + if(selected==folder.id) " · 已选" else "",modifier=Modifier.padding(8.dp),style=MaterialTheme.typography.labelSmall)
            }
        }
        item { TextButton(onClick={editing=LibraryFolder(name="")},enabled=!state.busy && state.error != "文件夹读取失败，原记录已保留"){Text("新建文件夹")} }
        folders.firstOrNull { it.id==selected }?.let { folder ->
            item { TextButton(onClick={editing=folder},enabled=!state.busy){Text("编辑文件夹")} }
        }
    }
    state.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    editing?.let { folder -> FolderEditor(member,folder,pieces,model,{editing=null}) { updated ->
        model.save(member,updated,baseline=FolderEditBaseline(folder.id,folder.takeIf { it.name.isNotEmpty() })); editing=null
    } }
}

@Composable
private fun FolderCover(member: String, folder: LibraryFolder, model: LibraryFolderViewModel) {
    Box(Modifier.fillMaxWidth().height(76.dp),contentAlignment=Alignment.Center) {
        if(folder.coverPiece != null) {
            val preview=remember(member,folder.id) { model.preview() }
            val state by preview.state.collectAsStateWithLifecycle()
            LaunchedEffect(member,folder.coverPiece) { preview.bind(member,folder.coverPiece,160,200) }
            DisposableEffect(preview) { onDispose { preview.clear() } }
            val ready=state as? ScorePreviewState.Ready
            if(ready?.target?.member==member && ready.image.canDisplay()) {
                androidx.compose.ui.viewinterop.AndroidView(factory={PreviewImageView(it)},update={it.show(ready.image)},
                    onRelease={it.clearPreview()},modifier=Modifier.fillMaxSize())
            } else Icon(Icons.Outlined.Folder,"乐谱封面暂不可用")
        } else if(folder.image != null) {
            val bitmap=remember(folder.image) { runCatching { val b=Base64.decode(folder.image,Base64.NO_WRAP); BitmapFactory.decodeByteArray(b,0,b.size) }.getOrNull() }
            if(bitmap != null) Image(bitmap.asImageBitmap(),"文件夹封面",Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
            else Icon(Icons.Outlined.Folder,null)
        } else Icon(Icons.Outlined.Folder,null)
    }
}

@Composable
private fun FolderEditor(member: String, original: LibraryFolder, pieces: List<PieceSummary>, model: LibraryFolderViewModel,
    close: () -> Unit, save: (LibraryFolder) -> Unit) {
    var name by rememberSaveable(original.id) { mutableStateOf(original.name) }
    var chosen by remember(original.id) { mutableStateOf(original.pieces) }
    var coverPiece by remember(original.id) { mutableStateOf(original.coverPiece) }
    var image by remember(original.id) { mutableStateOf(original.image) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) scope.launch { busy=true
            try { image=model.image(uri); coverPiece=null; message=null }
            catch(e: Exception) { message=e.message ?: "图片读取失败" }
            finally { busy=false }
        }
    }
    if(delete) AlertDialog(onDismissRequest={delete=false},title={Text("删除文件夹？")},text={Text("仅移除这个文件夹，曲谱和批注继续保留。")},
        confirmButton={TextButton(onClick={model.save(member,null,original.id,FolderEditBaseline(original.id,original));close()}){Text("删除文件夹")}},dismissButton={TextButton(onClick={delete=false}){Text("取消")}})
    AlertDialog(onDismissRequest={if(!busy)close()},title={Text("我的曲谱文件夹")},text={
        Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(name,{name=it.take(100)},label={Text("文件夹名称")},singleLine=true,modifier=Modifier.testTag("folder-name"))
            Text("可自由分类，同一首曲谱可加入多个文件夹。",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={picker.launch(arrayOf("image/*"))},enabled=!busy){Text("上传图片封面")}
            AdminChoice("使用乐谱首页作封面",coverPiece,listOf(null to "无乐谱封面")+pieces.map { it.id to listOfNotNull(it.organizerSequence,it.title).joinToString(" · ") }) { coverPiece=it; if(it!=null)image=null }
            if(image!=null) TextButton(onClick={image=null}){Text("清除图片封面")}
            pieces.forEach { piece -> Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(piece.id in chosen,{checked -> chosen=if(checked)chosen+piece.id else chosen-piece.id},modifier=Modifier.testTag("folder-piece-${piece.id}"))
                Column { Text(listOfNotNull(piece.organizerSequence,piece.title).joinToString(" · "))
                    Text((listOfNotNull(piece.categoryName)+piece.partNames).joinToString(" · "),style=MaterialTheme.typography.labelSmall) }
            } }
            message?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            if(original.name.isNotEmpty()) TextButton(onClick={delete=true},enabled=!busy){Text("删除文件夹")}
        }
    },confirmButton={TextButton(onClick={save(original.copy(name=name.trim(),pieces=chosen,coverPiece=coverPiece,image=image))},enabled=name.isNotBlank() && !busy){Text("保存")}},
        dismissButton={TextButton(onClick=close,enabled=!busy){Text("取消")}})
}
