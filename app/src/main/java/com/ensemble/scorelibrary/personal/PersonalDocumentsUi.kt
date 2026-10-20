package com.ensemble.scorelibrary.personal

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*

@Composable
internal fun PersonalDocumentsPanel(owner:PersonalOwner?,ownerNow:()->PersonalOwner?,modifier:Modifier=Modifier) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val store=remember(ownerNow,context.applicationContext) { PersonalDocumentStore(context.applicationContext,ownerNow) }
    val controller=remember(store,scope) { PersonalDocumentsController(store,scope) }
    DisposableEffect(controller) { onDispose { controller.close() } }
    LaunchedEffect(owner) { controller.bind(owner) }
    val state by controller.state.collectAsStateWithLifecycle()
    var pickerOwner by remember(owner?.key) { mutableStateOf<String?>(null) }
    var chosen by remember(owner?.key) { mutableStateOf<Uri?>(null) }
    var title by remember(owner?.key) { mutableStateOf("") }
    var edited by remember(owner?.key) { mutableStateOf(false) }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null && pickerOwner!=null && pickerOwner==ownerNow()?.key) {
            chosen=uri;title="个人PDF";edited=false
            val expected=pickerOwner
            scope.launch {
                val name=withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
                        if(it.moveToFirst())it.getString(0) else null
                    } }.getOrNull()
                }
                if(chosen==uri && expected==ownerNow()?.key && !edited && !name.isNullOrBlank())title=name.removeSuffix(".pdf").removeSuffix(".PDF")
            }
        }
    }
    PersonalDocumentsContent(state,modifier,
        onImport={pickerOwner=state.owner?.key;if(pickerOwner!=null)picker.launch(arrayOf("application/pdf"))},
        refresh=controller::refresh,trash=controller::trash,preview=controller::preview,open=controller::open,
        cancel=controller::cancelPreview,permanent=controller::permanent,previewSelected=controller::preview)
    chosen?.let { uri ->
        AlertDialog(onDismissRequest={chosen=null},title={Text("导入个人PDF")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("复制到当前账号的本机个人文件区，外部原文件保留，不上传团队曲库。")
            OutlinedTextField(title,{title=it;edited=true},label={Text("文件名称（最多200字）")},modifier=Modifier.testTag("personal-import-title"))
        }},confirmButton={TextButton(onClick={chosen=null;controller.importPdf(uri,title)},
            enabled=!state.busy && title.trim().length in 1..200 && title.none(Char::isISOControl) && pickerOwner==ownerNow()?.key,
            modifier=Modifier.testTag("personal-import-confirm")) { Text("确认导入") }},
            dismissButton={TextButton(onClick={chosen=null}) { Text("取消") }})
    }
    state.opened?.let { opened ->
        PersonalPdfViewer(state.owner?.key,opened.document,controller::verifiedPdf,controller::closePdf)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun PersonalDocumentsContent(state:PersonalFilesState,modifier:Modifier=Modifier,onImport:()->Unit,
    refresh:()->Unit,trash:(String,Boolean)->Unit,preview:(String)->Unit,open:(String)->Unit,
    cancel:()->Unit,permanent:(Boolean)->Unit,previewSelected:(Set<String>)->Unit={}) {
    var recycle by remember(state.owner?.key) { mutableStateOf(false) }
    var query by remember(state.owner?.key) { mutableStateOf("") }
    var move by remember(state.owner?.key) { mutableStateOf<PersonalDocument?>(null) }
    var selected by remember(state.owner?.key) { mutableStateOf(emptySet<String>()) }
    val eligible=state.snapshot?.documents.orEmpty().filter { it.trashed && !it.retiring }.map { it.id }.toSet()
    LaunchedEffect(eligible) { selected=selected.intersect(eligible) }
    Column(modifier.fillMaxSize().padding(12.dp).testTag("personal-files"),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("当前账号的个人文件 · 仅本机",style=MaterialTheme.typography.titleMedium)
        Text("与团队源谱分开保存，不随团队同步。仅管理本账号导入的副本；外部原文件保留。现有曲库备份暂不包含此文件区。",style=MaterialTheme.typography.bodySmall)
        if(state.owner==null) {
            Text(state.message ?: "请先登录账号，或等待账号核验完成。",Modifier.testTag("personal-login-required"));return@Column
        }
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick=onImport,enabled=!state.busy,modifier=Modifier.testTag("personal-import")) { Text("导入个人PDF") }
            FilterChip(recycle,{recycle=!recycle;selected=emptySet()},label={Text(if(recycle)"回收站" else "查看回收站")},modifier=Modifier.testTag("personal-recycle"))
            TextButton(onClick=refresh,enabled=!state.busy,modifier=Modifier.testTag("personal-refresh")) { Text("刷新／重试") }
        }
        OutlinedTextField(query,{query=it},label={Text("搜索个人文件")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("personal-search"))
        if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        state.message?.let { Text(it,Modifier.testTag("personal-status")) }
        val rows=state.snapshot?.documents.orEmpty().filter { it.trashed==recycle && it.title.contains(query.trim(),true) }
        if(recycle)FlowRow(verticalArrangement=Arrangement.spacedBy(4.dp)) {
            TextButton(onClick={selected=rows.map { it.id }.filter { it in eligible }.toSet()},enabled=!state.busy,
                modifier=Modifier.testTag("personal-select-visible")) { Text("全选搜索结果") }
            TextButton(onClick={selected=emptySet()},enabled=!state.busy) { Text("取消选择") }
            TextButton(onClick={previewSelected(selected)},enabled=!state.busy && selected.size in 1..100,
                modifier=Modifier.testTag("personal-permanent-selected")) { Text("彻底删除所选（${selected.size}份）") }
        }
        if(recycle && selected.size>100)Text("一次最多100份个人文件，请减少选择后合并预览。")
        if(rows.isEmpty() && !state.busy)Text(if(recycle)"本账号回收站为空" else "暂无本机个人文件")
        LazyColumn(Modifier.weight(1f)) { items(rows,key={it.id}) { d ->
            ListItem(headlineContent={Text(d.title)},supportingContent={Text("${d.pages} 页 · ${personalSize(d.size)}"+if(d.retiring)" · 清理未完成" else "")},
                leadingContent={if(recycle)Checkbox(d.id in selected,{checked -> selected=if(checked)selected+d.id else selected-d.id},
                    enabled=!state.busy && !d.retiring,modifier=Modifier.testTag("personal-select-${d.id}"))},
                trailingContent={Row {
                    if(!recycle)TextButton(onClick={open(d.id)},enabled=!state.busy,modifier=Modifier.testTag("personal-open-${d.id}")) { Text("打开") }
                    TextButton(onClick={move=d},enabled=!state.busy && !d.retiring,modifier=Modifier.testTag("personal-trash-${d.id}")) { Text(if(recycle)"恢复" else "删除") }
                    if(recycle)TextButton(onClick={preview(d.id)},enabled=!state.busy && !d.retiring,modifier=Modifier.testTag("personal-permanent-${d.id}")) { Text("彻底删除") }
                }})
        } }
    }
    move?.let { d -> AlertDialog(onDismissRequest={move=null},title={Text(if(d.trashed)"恢复个人文件" else "移入个人回收站")},
        text={Text(d.title+if(d.trashed)"\n恢复本账号的原副本和身份。" else "\n仅移入本账号回收站，可恢复，不改变团队源谱。")},
        confirmButton={TextButton(onClick={move=null;trash(d.id,!d.trashed)},enabled=!state.busy,modifier=Modifier.testTag("personal-trash-confirm")) { Text("确认") }},
        dismissButton={TextButton(onClick={move=null}) { Text("取消") }}) }
    state.plan?.let { plan ->
        var checked by remember(plan) { mutableStateOf(false) }
        AlertDialog(onDismissRequest={if(!state.busy)cancel()},title={Text("彻底删除本人个人文件")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("仅清除本账号在本机登记的 ${plan.documents.size} 份副本，共 ${personalSize(plan.documents.sumOf { it.size })}。不可恢复，外部原文件、其他账号和团队源谱均不在本次范围。")
            plan.documents.forEach { Text(it.title) }
            Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(checked,{checked=it},enabled=!state.busy,modifier=Modifier.testTag("personal-permanent-check"))
                Text("我已核对文件及影响范围，确认不可恢复")
            }
        }},confirmButton={TextButton(onClick={permanent(checked)},enabled=checked && !state.busy && state.owner?.key==plan.owner,
            modifier=Modifier.testTag("personal-permanent-confirm")) { Text("确认彻底删除") }},
            dismissButton={TextButton(onClick=cancel,enabled=!state.busy) { Text("取消") }})
    }
}

private fun personalSize(size:Long)=if(size<1024*1024)"${(size+1023)/1024} KB" else "${size/(1024*1024)} MB"

@Composable
internal fun PersonalPdfViewer(owner:String?,document:PersonalDocument,verify:suspend (String)->PersonalPdf,close:()->Unit) {
    var page by remember(owner,document.id) { mutableIntStateOf(0) }
    var bitmap by remember(owner,document.id) { mutableStateOf<Bitmap?>(null) }
    var error by remember(owner,document.id) { mutableStateOf<String?>(null) }
    var loading by remember(owner,document.id) { mutableStateOf(false) }
    LaunchedEffect(owner,document.id,page) {
        loading=true;error=null;bitmap=null
        try {
            val pdf=verify(document.id)
            val image=withContext(Dispatchers.IO) {
                ParcelFileDescriptor.open(pdf.file,ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        check(renderer.pageCount==document.pages)
                        renderer.openPage(page).use { p ->
                            check(p.width>0 && p.height>0)
                            val scale=minOf(1280f/p.width,2048f/p.height)
                            Bitmap.createBitmap((p.width*scale).toInt().coerceAtLeast(1),(p.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888).also {
                                it.eraseColor(Color.WHITE);p.render(it,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }
            }
            verify(document.id);bitmap=image
        } catch(cancelled:CancellationException) { throw cancelled }
        catch(_:Exception) { error="此个人PDF暂时无法打开，请返回并刷新核对文件" }
        finally { loading=false }
    }
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(document.title,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                TextButton(onClick=close,modifier=Modifier.testTag("personal-pdf-close")) { Text("返回个人文件") }
            }
            Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
                if(loading)CircularProgressIndicator()
                error?.let { Text(it) }
                bitmap?.let { Image(it.asImageBitmap(),"个人PDF第${page+1}页",Modifier.fillMaxSize(),contentScale=ContentScale.Fit) }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                TextButton(onClick={page--},enabled=!loading && page>0) { Text("上一页") }
                Text("${page+1} / ${document.pages}")
                TextButton(onClick={page++},enabled=!loading && page+1<document.pages) { Text("下一页") }
            }
        } }
    }
}
