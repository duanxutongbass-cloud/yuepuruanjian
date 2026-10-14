package com.ensemble.scorelibrary.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.ensemble.scorelibrary.domain.*

enum class PadLayout { Compact, Medium, Expanded, Large }
fun padLayout(widthDp: Int) = when {
    widthDp < 600 -> PadLayout.Compact
    widthDp < 840 -> PadLayout.Medium
    widthDp < 1200 -> PadLayout.Expanded
    else -> PadLayout.Large
}
enum class RootPage(val route: String, val label: String, val icon: ImageVector) {
    Library("library", "曲库", Icons.Outlined.LibraryMusic),
    Concerts("concerts", "节目单", Icons.Outlined.Event),
    Profile("profile", "我的", Icons.Outlined.Person),
    Admin("admin", "管理", Icons.Outlined.Settings)
}
object Routes {
    const val PIECE = "piece/{pieceId}"
    const val CONCERT = "concert/{concertId}"
    const val VIEWER = "viewer/{pieceId}/{partId}?concertId={concertId}&itemId={itemId}"
    fun piece(id: String) = "piece/${Uri.encode(id)}"
    fun concert(id: String) = "concert/${Uri.encode(id)}"
    fun viewer(piece: String, part: String, concert: String? = null, item: String? = null): String {
        val route = "viewer/${Uri.encode(piece)}/${Uri.encode(part)}"
        return if (concert == null) route else "$route?concertId=${Uri.encode(concert)}&itemId=${Uri.encode(item.orEmpty())}"
    }
    fun isViewer(route: String?) = route?.startsWith("viewer/") == true
    fun root(route: String?) = when {
        route == "profile" -> RootPage.Profile
        route == "admin" -> RootPage.Admin
        route == "concerts" || route?.startsWith("concert/") == true -> RootPage.Concerts
        else -> RootPage.Library
    }
}
private fun NavHostController.openRoot(page: RootPage) {
    navigate(page.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreLibraryApp(vm: LibraryViewModel, pdf: com.ensemble.scorelibrary.pdf.PdfReaderViewModel? = null,
    administration: AdministrationViewModel? = null, preview: ScorePreviewViewModel? = null) {
    val archive = com.ensemble.scorelibrary.backup.LocalArchive.current
    val archiveState = archive?.state?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(archiveState?.revision) { if ((archiveState?.revision ?: 0) > 0) vm.retry() }
    val archiveMember by vm.selectedMember.collectAsStateWithLifecycle()
    LaunchedEffect(archiveMember) { archive?.bind(archiveMember) }
    if (administration != null) {
        val administrationState by administration.state.collectAsStateWithLifecycle()
        LaunchedEffect(administrationState.revision) { if (administrationState.revision > 0) vm.retry() }
        LaunchedEffect(administrationState.createdMember) {
            administrationState.createdMember?.let { vm.selectMember(it); administration.acknowledgeMember() }
        }
    }
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val envelope by vm.state.collectAsStateWithLifecycle()
    val memberId by vm.selectedMember.collectAsStateWithLifecycle()
    val librarySelection = rememberSaveable(memberId, saver = LibrarySelection.Saver) { LibrarySelection() }
    val fullscreen = pdf?.fullscreen?.collectAsStateWithLifecycle()?.value == true
    val current = if (memberId == envelope.memberId) envelope else envelope.copy(state = UiState.Loading)
    val reader = Routes.isViewer(route)
    val headerDashboard = (current.state as? UiState.Ready)?.presentation?.dashboard
    val headerPreview = preview?.state?.collectAsStateWithLifecycle()?.value as? ScorePreviewState.Ready
    val headerPart = headerPreview?.takeIf { route == "library" && it.target.member == memberId &&
        it.target.piece == librarySelection.selected.value && it.image.canDisplay() }?.score?.part?.name

    LaunchedEffect(route, memberId, current.state) {
        if (route != "library" || current.state !is UiState.Ready) preview?.clear()
    }
    val ink = com.ensemble.scorelibrary.annotation.LocalAnnotations.current
    val inkState = ink?.state?.collectAsStateWithLifecycle()?.value
    var confirmLeave by remember { mutableStateOf(false) }
    val leaveReader: () -> Unit = {
        if (reader && inkState?.transitionBlocked == true) confirmLeave = true
        else { ink?.suspendSession(); nav.popBackStack() }
    }
    androidx.activity.compose.BackHandler(reader && !fullscreen) { leaveReader() }
    LaunchedEffect(memberId) {
        if (ink?.state?.value?.scope?.member?.let { it != memberId } == true) ink.suspendSession()
    }
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text("批注尚未完成保存") },
        text = { Text("建议留下并重试。继续离开会停止保存，待处理内容仅在当前进程内保留；返回同一成员的原页面后可重试。关闭应用或系统回收可能丢失。") },
        confirmButton = { TextButton(onClick = { confirmLeave = false; ink?.suspendSession(); nav.popBackStack() },
            enabled = inkState?.gestureActive != true) { Text("保留在内存并离开") } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("留下处理") } })
    val viewer = (((current.state as? UiState.Ready)?.presentation?.content as? DomainResult.Success)?.value as? ScreenContent.Viewer)
    LaunchedEffect(reader, viewer) {
        if (reader && viewer == null) ink?.suspendSession()
    }
    val pdfBinding = pdf?.binding?.collectAsStateWithLifecycle()?.value
    val pdfAnchor = pdf?.anchor?.collectAsStateWithLifecycle()?.value
    val playing = pdf?.playMode?.collectAsStateWithLifecycle()?.value == true
    val preparing = pdf?.performance?.collectAsStateWithLifecycle()?.value is com.ensemble.scorelibrary.pdf.PerformanceState.Checking
    val readerTitle = if (viewer != null && pdfAnchor == memberId to viewer.score)
        pdfBinding?.takeIf { it.first == memberId }?.second?.title ?: viewer.score.title else viewer?.score?.title ?: "看谱"
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(reader, view) {
        var context = view.context
        while (context is android.content.ContextWrapper && context !is android.app.Activity) context = context.baseContext
        val activity = context as? android.app.Activity
        val controller = activity?.let { androidx.core.view.WindowCompat.getInsetsController(it.window, view) }
        if (reader) {
            controller?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.ime())
        } else controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
    }
    CompositionLocalProvider(com.ensemble.scorelibrary.pdf.LocalPdfReader provides pdf,
        LocalAdministrationViewModel provides administration, LocalScorePreview provides preview,
        LocalLibrarySelection provides librarySelection) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val sideNavigation = maxWidth >= 600.dp
    Row(Modifier.fillMaxSize()) {
    if (!reader && sideNavigation) WorkspaceNavigation(Routes.root(route)) { nav.openRoot(it) }
    Scaffold(modifier = Modifier.weight(1f), containerColor = ScorePalette.Workspace, topBar = {
        if (!reader || !fullscreen) {
        TopAppBar(colors = TopAppBarDefaults.topAppBarColors(
            containerColor = if (reader) ScorePalette.Navigation else ScorePalette.Workspace,
            titleContentColor = if (reader) androidx.compose.ui.graphics.Color.White else ScorePalette.Ink,
            navigationIconContentColor = if (reader) androidx.compose.ui.graphics.Color.White else ScorePalette.Ink,
            actionIconContentColor = if (reader) androidx.compose.ui.graphics.Color.White else ScorePalette.Ink), title = {
                Column {
                    Text(if (reader) readerTitle else if (route == "library") "重奏团 · 曲谱库" else "重奏团曲谱库", maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if (route == "library") headerDashboard?.profile?.let { profile ->
                        Text("${profile.name} · ${headerPart ?: "请选择曲目"}", Modifier.testTag("library-member-part"),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            },
            navigationIcon = {
                if (reader || route == Routes.PIECE || route == Routes.CONCERT) IconButton(
                    onClick = leaveReader, modifier = Modifier.testTag("back")) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                }
            }, actions = { IconButton(onClick = vm::retry, enabled = !reader || !playing && !preparing && inkState?.transitionBlocked != true) { Icon(Icons.Outlined.Refresh, "刷新") } })
        }
    }, bottomBar = {
        if (!reader && !sideNavigation) NavigationBar(Modifier.testTag("bottom-navigation")) {
            RootPage.entries.filterNot { it == RootPage.Admin }.forEach { page -> NavigationBarItem(
                modifier = Modifier.testTag("nav-${page.route}"),
                selected = Routes.root(route) == page || page == RootPage.Profile && route == "admin", onClick = { nav.openRoot(page) },
                icon = { Icon(page.icon, page.label) }, label = { Text(page.label) }) }
        }
    }) { padding ->
        NavHost(nav, "library", Modifier.padding(padding)) {
            RootPage.entries.forEach { page -> composable(page.route) {
                RouteScreen(ScreenRequest.Home, page, current, vm, nav)
            } }
            composable(Routes.PIECE) { entry -> RouteScreen(
                ScreenRequest.Piece(requireNotNull(entry.arguments?.getString("pieceId"))), RootPage.Library, current, vm, nav) }
            composable(Routes.CONCERT) { entry -> RouteScreen(
                ScreenRequest.Concert(requireNotNull(entry.arguments?.getString("concertId"))), RootPage.Concerts, current, vm, nav) }
            composable(Routes.VIEWER, arguments = listOf(
                androidx.navigation.navArgument("concertId") { nullable = true; defaultValue = null },
                androidx.navigation.navArgument("itemId") { nullable = true; defaultValue = null }
            )) { entry ->
                val args = requireNotNull(entry.arguments)
                RouteScreen(ScreenRequest.Viewer(requireNotNull(args.getString("pieceId")),
                    requireNotNull(args.getString("partId")), args.getString("concertId"), args.getString("itemId")),
                    RootPage.Library, current, vm, nav)
            }
        }
    }
    }
    }
    }
}
@Composable
private fun RouteScreen(request: ScreenRequest, root: RootPage, envelope: ScreenState,
    vm: LibraryViewModel, nav: NavHostController) {
    LaunchedEffect(request) { vm.show(request) }
    val networkSession = com.ensemble.scorelibrary.sync.LocalHttpSession.current
    LibraryScreen(if (request == envelope.request) envelope.state else UiState.Loading, root,
        vm::retry, onMember = { id ->
            if (networkSession != null) { if (id == null) networkSession.logout() } else vm.selectMember(id)
            if (id == null) nav.openRoot(RootPage.Profile)
        },
        onPiece = { nav.navigate(Routes.piece(it)) { launchSingleTop = true } },
        onConcert = { nav.navigate(Routes.concert(it)) { launchSingleTop = true } },
        onPart = { piece, part -> nav.navigate(Routes.viewer(piece, part)) { launchSingleTop = true } },
        onConcertScore = { concert, item -> nav.navigate(Routes.viewer(item.pieceId, item.part.id, concert, item.itemId)) { launchSingleTop = true } })
}

@Composable
fun LibraryScreen(state: UiState, root: RootPage, onRetry: () -> Unit = {},
    onMember: (String?) -> Unit = {}, onPiece: (String) -> Unit = {}, onConcert: (String) -> Unit = {},
    onPart: (String, String) -> Unit = { _, _ -> },
    onConcertScore: (String, ResolvedConcertItem) -> Unit = { _, _ -> }) {
    val pdf = com.ensemble.scorelibrary.pdf.LocalPdfReader.current
    val ready = state as? UiState.Ready
    val dashboard = ready?.presentation?.dashboard
    var managementTab by rememberSaveable(dashboard?.profile?.id) { mutableStateOf<Int?>(null) }
    var managedId by rememberSaveable(dashboard?.profile?.id) { mutableStateOf<String?>(null) }
    var create by rememberSaveable(dashboard?.profile?.id) { mutableStateOf(false) }
    val administration = LocalAdministrationViewModel.current
    if (managementTab != null && dashboard != null && administration != null &&
        (dashboard.profile?.canManage == true || dashboard.members.isEmpty())) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { managementTab = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (root == RootPage.Library) "导入曲谱" else if (root == RootPage.Concerts) "节目单管理" else "管理中心", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = { managementTab = null }, modifier = Modifier.testTag("close-inline-management")) { Icon(Icons.Outlined.Close, "返回页面") }
                }
                AdministrationScreen(dashboard, administration, managementTab!!, managedId, create, contextOnly = root != RootPage.Profile)
            } }
        }
    }
    val viewer = (ready?.presentation?.content as? DomainResult.Success)?.value as? ScreenContent.Viewer
    if (viewer != null && pdf != null) {
        com.ensemble.scorelibrary.pdf.PdfReaderScreen(ready.presentation.dashboard.profile?.id.orEmpty(), viewer.score, pdf, ready.offline,
            ready.presentation.dashboard.profile?.canManage == true, viewer.concert)
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = padLayout(maxWidth.value.toInt())
        Box(Modifier.fillMaxSize().padding(horizontal = if (layout == PadLayout.Compact) 16.dp else 32.dp),
            contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 1440.dp).fillMaxSize()) {
                when (state) {
                    UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(40.dp).testTag("loading"))
                    }
                    is UiState.Error -> StatusPanel("暂时无法打开", errorText(state.code), Icons.Outlined.ErrorOutline) {
                        Button(onClick = onRetry) { Icon(Icons.Outlined.Refresh, null); Text("重试") }
                        TextButton(onClick = { onMember(null) }) { Text("重新选择成员") }
                    }
                    is UiState.Ready -> {
                        if (state.offline) OfflineStatus()
                        when (val content = state.presentation.content) {
                            is DomainResult.Failure -> StatusPanel("暂时无法打开", errorText(content.error.code), Icons.Outlined.ErrorOutline) {
                                Button(onClick = onRetry) { Text("重试") }
                            }
                            is DomainResult.Success -> when (val page = content.value) {
                                ScreenContent.Home -> HomePage(root, state.presentation.dashboard, layout, onMember, onPiece, onConcert, onPart) { tab, id, new ->
                                    managedId = id; create = new; managementTab = tab
                                }
                                is ScreenContent.Piece -> DetailColumns(layout, page.title, "可见声部 · ${page.parts.size}") {
                                    if (page.parts.isEmpty()) StatusPanel("暂无可见声部", "请联系管理员确认授权", Icons.Outlined.Lock)
                                    else LazyColumn { items(page.parts, key = { it.id }) { part ->
                                        Entry(part.name, "", Icons.Outlined.MusicNote, "part-${part.id}") { onPart(page.id, part.id) }
                                    } }
                                }
                                is ScreenContent.Concert -> DetailColumns(layout, page.concert.title, "个人演出谱 · ${page.concert.items.size} 首") {
                                    Column {
                                    page.concert.items.firstOrNull()?.let { first ->
                                        Button(onClick = { onConcertScore(page.concert.id, first) },
                                            modifier = Modifier.padding(vertical = 12.dp).testTag("open-concert-reader")) {
                                            Icon(Icons.Outlined.PlayArrow, null); Text("打开演出谱")
                                        }
                                    }
                                    if (dashboard?.profile?.canManage == true && administration != null) TextButton(onClick = {
                                        managedId = page.concert.id; create = false; managementTab = 4
                                    }, modifier = Modifier.testTag("edit-concert-here")) { Icon(Icons.Outlined.Edit, null); Text("编辑节目单") }
                                    if (page.concert.items.isEmpty()) StatusPanel("节目单为空", "暂无启用曲目", Icons.Outlined.Event)
                                    else LazyColumn { items(page.concert.items, key = { it.itemId }) { item ->
                                        Entry(item.title, "${item.part.name} · ${item.segments.size} 个谱段", Icons.Outlined.MusicNote,
                                            "concert-item-${item.itemId}") { onConcertScore(page.concert.id, item) }
                                    } }
                                    }
                                }
                                is ScreenContent.Viewer -> ViewerPage(page.score, state.missingFiles, onRetry)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun OfflineStatus() {
    var showExplanation by remember { mutableStateOf(false) }
    TextButton(onClick = { showExplanation = true }, modifier = Modifier.testTag("offline"),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
        Icon(Icons.Outlined.CloudOff, null, Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("离线 · 说明", style = MaterialTheme.typography.labelMedium)
    }
    if (showExplanation) AlertDialog(onDismissRequest = { showExplanation = false },
        modifier = Modifier.testTag("offline-explanation"), title = { Text("当前离线") },
        text = { Text("本地曲库仍可查看。已有本地 PDF 和批注可离线使用；能否打开具体声部仍取决于当前成员授权和文件是否完整。缺失文件与权限错误会在谱页区域显示。本应用尚未实现 NAS 同步。") },
        confirmButton = { TextButton(onClick = { showExplanation = false }, modifier = Modifier.testTag("offline-close")) { Text("知道了") } })
}

@Composable
private fun HomePage(root: RootPage, dashboard: Dashboard, layout: PadLayout,
    onMember: (String?) -> Unit, onPiece: (String) -> Unit, onConcert: (String) -> Unit,
    onPart: (String, String) -> Unit, onManage: (Int, String?, Boolean) -> Unit) {
    val administration = LocalAdministrationViewModel.current
    val networkSession = com.ensemble.scorelibrary.sync.LocalHttpSession.current
    if (root == RootPage.Admin && administration != null) {
        AdministrationScreen(dashboard, administration)
        return
    }
    if (root == RootPage.Profile) {
        Heading("我的", dashboard.profile?.name ?: "未选择成员")
        LazyColumn {
            dashboard.profile?.let { profile ->
                item { ListItem(headlineContent = { Text(profile.name) }, supportingContent = { Text(profile.defaultPartName ?: "默认声部暂不可用") }, leadingContent = { Icon(Icons.Outlined.Person, null) }) }
                item { TextButton(onClick = { onMember(null) }) { Icon(Icons.AutoMirrored.Outlined.Logout, null); Text("退出当前成员") } }
            }
            if (networkSession == null) item { MemberPicker(dashboard, onMember) }
            if (administration != null && (dashboard.profile?.canManage == true || dashboard.members.isEmpty())) {
                item { TextButton(onClick = { onManage(0, null, false) }, modifier = Modifier.testTag("profile-management")) { Icon(Icons.Outlined.Settings, null); Text(if (dashboard.members.isEmpty()) "创建管理员" else "管理中心") } }
                if (dashboard.profile?.canManage == true) item { TextButton(onClick = { onManage(2, null, false) }, modifier = Modifier.testTag("profile-part-grants")) { Icon(Icons.Outlined.AdminPanelSettings, null); Text("成员与声部授权") } }
            }
            item { com.ensemble.scorelibrary.backup.LocalArchive.current?.let { com.ensemble.scorelibrary.backup.ArchivePanel(dashboard, it) } }
            item { com.ensemble.scorelibrary.core.pedal.LocalPedal.current?.let { com.ensemble.scorelibrary.core.pedal.PedalSettingsScreen(it) } }
        }
        return
    }
    if (dashboard.profile == null) {
        StatusPanel("选择当前成员", if (dashboard.members.isEmpty()) "请到「我的」创建首位本地管理员" else "本地测试身份，不是在线登录", Icons.Outlined.Person) {
            if (networkSession == null) MemberPicker(dashboard, onMember)
        }
        return
    }
    when (root) {
        RootPage.Library -> {
            if (dashboard.profile.canManage && administration != null) TextButton(onClick = { onManage(0, null, false) }, modifier = Modifier.testTag("library-import")) { Icon(Icons.Outlined.FileOpen, null); Text("导入曲谱") }
            PieceWorkspace(dashboard.pieces, dashboard.profile.id, onPart = onPart, onPiece = onPiece)
        }
        RootPage.Concerts -> {
            Heading("节目单", "${dashboard.concerts.size} 场演出")
            if (dashboard.profile.canManage && administration != null) TextButton(onClick = { onManage(4, null, true) }, modifier = Modifier.testTag("concert-create")) { Icon(Icons.Outlined.Add, null); Text("新建节目单") }
            ConcertWorkspace(dashboard.concerts, dashboard.profile.id, onConcert)
        }
        RootPage.Admin -> {
            Heading("管理", dashboard.profile.name)
            if (!dashboard.profile.canManage) StatusPanel("仅管理员可访问", "请联系乐团管理员", Icons.Outlined.Lock)
            else LazyColumn {
                item { ListItem(headlineContent = { Text("曲目") }, supportingContent = { Text("${dashboard.pieces.size} 首") }) }
                item { ListItem(headlineContent = { Text("演出") }, supportingContent = { Text("${dashboard.concerts.size} 场") }) }
                item { ListItem(headlineContent = { Text("成员") }, supportingContent = { Text("${dashboard.members.size} 位") }) }
            }
        }
        RootPage.Profile -> Unit
    }
}
@Composable
private fun MemberPicker(dashboard: Dashboard, onMember: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    if (dashboard.members.isEmpty()) Text("暂无成员")
    else Box {
        OutlinedButton(onClick = { expanded = true }) { Icon(Icons.Outlined.Person, null); Text("选择成员") }
        DropdownMenu(expanded, { expanded = false }) {
            dashboard.members.forEach { member -> DropdownMenuItem(text = { Text(member.name) },
                onClick = { expanded = false; onMember(member.id) }) }
        }
    }
}
@Composable
private fun Entry(title: String, subtitle: String, icon: ImageVector, tag: String, onClick: () -> Unit) {
    Column {
        ListItem(headlineContent = { Text(title) }, supportingContent = { if (subtitle.isNotBlank()) Text(subtitle) },
            leadingContent = { Icon(icon, null) }, trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
            modifier = Modifier.clickable(onClick = onClick).testTag(tag))
        HorizontalDivider()
    }
}
@Composable
private fun DetailColumns(layout: PadLayout, title: String, subtitle: String, content: @Composable () -> Unit) {
    if (layout == PadLayout.Expanded || layout == PadLayout.Large) {
        Row(Modifier.fillMaxSize().padding(top = 24.dp).testTag("detail-columns"), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(0.32f).fillMaxHeight().verticalScroll(rememberScrollState())) { Heading(title, subtitle) }
            VerticalDivider()
            Box(Modifier.weight(0.68f).fillMaxHeight()) { content() }
        }
    } else Column(Modifier.fillMaxSize()) { Heading(title, subtitle); Box(Modifier.weight(1f)) { content() } }
}
@Composable
private fun ViewerPage(score: ResolvedConcertItem, missing: Boolean, retry: () -> Unit) {
    Heading(score.title, score.part.name)
    if (missing) StatusPanel("本地曲谱文件缺失", "请联系管理员补齐曲谱文件", Icons.AutoMirrored.Outlined.InsertDriveFile) {
        Button(onClick = retry) { Icon(Icons.Outlined.Refresh, null); Text("重新检查") }
    } else Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f)) { items(score.segments, key = { it.bindingId }) { segment ->
            ListItem(headlineContent = { Text("第 ${segment.startPage} 至 ${segment.endPage} 页") },
                supportingContent = { Text(cacheText(segment.cacheStatus)) },
                leadingContent = { Icon(Icons.Outlined.Description, null) })
        } }
        Text("曲谱阅读视图待接入", Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable
private fun Heading(title: String, subtitle: String) {
    Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
private fun StatusPanel(title: String, description: String, icon: ImageVector, actions: @Composable ColumnScope.() -> Unit = {}) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
        Icon(icon, null, Modifier.size(32.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (description.isNotBlank()) Text(description)
        actions()
    }
}
fun errorText(code: ErrorCode?) = when (code) {
    ErrorCode.UNAUTHORIZED -> "当前成员没有该声部的访问权限"
    ErrorCode.MEMBER_MISSING -> "成员不存在或已归档，请重新选择成员"
    ErrorCode.PIECE_MISSING -> "曲目不存在或已归档"
    ErrorCode.CONCERT_MISSING -> "演出不存在或已归档"
    ErrorCode.DEFAULT_PART_MISSING, ErrorCode.PIECE_PART_MISSING -> "声部分配不完整，请联系管理员"
    ErrorCode.ACTIVE_VERSION_MISSING, ErrorCode.PDF_MISSING -> "当前曲谱版本缺失，请联系管理员"
    ErrorCode.BINDING_MISSING, ErrorCode.INVALID_PAGE_RANGE, ErrorCode.MOVEMENT_MISMATCH -> "谱段或页码配置不完整，请联系管理员"
    null -> "读取失败，请重试"
    else -> "曲谱配置存在冲突，请联系管理员"
}
private fun cacheText(status: com.ensemble.scorelibrary.core.model.CacheStatus) = when (status) {
    com.ensemble.scorelibrary.core.model.CacheStatus.CACHED -> "已缓存"
    com.ensemble.scorelibrary.core.model.CacheStatus.OUTDATED -> "缓存需更新"
    com.ensemble.scorelibrary.core.model.CacheStatus.FAILED -> "缓存失败"
    com.ensemble.scorelibrary.core.model.CacheStatus.NOT_CACHED -> "未缓存"
    else -> "缓存处理中"
}
@Preview(name = "Portrait large Pad", widthDp = 1280, heightDp = 1800)
@Preview(name = "Standard Pad", widthDp = 800, heightDp = 1200)
@Composable
private fun LibraryPreview() {
    MaterialTheme { LibraryScreen(UiState.Ready(Presentation(Dashboard(emptyList(), Profile("m", "成员", "小提琴 I", false),
        listOf(PieceSummary("p", "天空之城", "久石让"))), DomainResult.Success(ScreenContent.Home)), false), RootPage.Library) }
}
