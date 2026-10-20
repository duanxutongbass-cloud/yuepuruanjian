package com.ensemble.scorelibrary.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject

data class TeamAccountState(val server: String = TEAM_SERVICE_URL, val principal: TeamPrincipal? = null,
    val devices: List<TeamDevice> = emptyList(), val members: List<TeamMember> = emptyList(),
    val invitation: String? = null, val busy: Boolean = false, val message: String = "",
    val libraryMember: String? = null, val syncMessage: String = "", val offline: Boolean = false, val verifiedUntil: Long = 0,
    val libraryBlocked: Boolean = true, val membership: TeamMembership? = null,
    val joinCodeRevision: Long? = null, val joinCodeConfigured: Boolean = false, val joinCode: String? = null,
    val joinRequests: List<TeamJoinRequest> = emptyList(), val joinRequestTotal: Int = 0,
    val teams:List<AccountTeam> = emptyList()) {
    override fun toString() = "TeamAccountState(signedIn=${principal != null},membership=${principal?.membership},busy=$busy,credentials=redacted)"
}

@HiltViewModel
class TeamAccountViewModel @Inject constructor(private val gateway: TeamAccountGateway,
    private val scores: com.ensemble.scorelibrary.sync.TeamScoreSync? = null,
    private val runs: com.ensemble.scorelibrary.pdf.PerformanceRuns? = null) : ViewModel() {
    private val mutable = MutableStateFlow(TeamAccountState(server = gateway.server))
    val state = mutable.asStateFlow()
    val downloads:kotlinx.coroutines.flow.StateFlow<com.ensemble.scorelibrary.sync.TeamDownloadState> =
        scores?.downloads ?: MutableStateFlow(com.ensemble.scorelibrary.sync.TeamDownloadState())
    private var session: TeamSession? = null
    private var expiry: Job? = null
    private var deadline: Long = 0
    private var expiryToken: String? = null
    private var pendingApplication: JSONObject? = null
    private var activeOperation: Job? = null
    private var leaving: Job? = null
    private var exiting=false
    private val pendingReviews = mutableMapOf<String, JSONObject>()
    private val pendingTeams=mutableMapOf<String,JSONObject>()

    fun switchTeam(id:String) {
        if(mutable.value.busy || session?.principal?.teamId==id)return
        operation {
            verifySession()
            val current=checkNotNull(session)
            check(current.principal.multiTeamProtocol==1 && !current.offline) { "请联网核验后切换团队" }
            check(mutable.value.teams.any { it.id==id && it.active }) { "当前账号不能进入这个团队" }
            runs?.end();expiry?.cancel();expiry=null;scores?.deactivate()
            mutable.update { it.copy(libraryMember=null,libraryBlocked=true,members=emptyList(),devices=emptyList(),
                joinCode=null,joinRequests=emptyList(),invitation=null,syncMessage="正在切换团队") }
            val next=withContext(Dispatchers.IO) { gateway.switchTeam(current,id) }
            check(next.principal.accountId==current.principal.accountId && next.principal.teamId==id && !next.offline)
            beginSession(next)
        }
    }
    fun createTeam(name:String)=operation {
        val current=checkNotNull(session)
        check(current.principal.multiTeamProtocol==1 && !current.offline)
        val title=name.trim().also { require(it.length in 1..100 && it.none { c->c.isISOControl() }) }
        val key="create/$title"
        val body=pendingTeams.getOrPut(key) { JSONObject().put("requestId",java.util.UUID.randomUUID().toString())
            .put("name",title).put("confirmed",true) }
        var result=call(current,"/v1/me/teams/create",body)
        while(result.optBoolean("pending",false)) {
            require(result.getString("requestId")==body.getString("requestId"))
            mutable.update {it.copy(message="正在创建团队，可以返回；已提交操作会继续处理")}
            delay(500)
            result=call(current,"/v1/me/team-operations/"+body.getString("requestId"))
        }
        require(java.util.UUID.fromString(result.getString("teamId")).toString()==result.getString("teamId"))
        require(result.getString("teamName")==title && result.get("created")==true)
        if(result.has("requestId"))require(result.getString("requestId")==body.getString("requestId"))
        loadDetailsWhenOnline(current);pendingTeams.remove(key)
        mutable.update { it.copy(message="团队已创建，你是该团队管理员；请选择团队进入，生成乐团码邀请成员") }
    }
    fun applyOtherTeam(code:String)=operation {
        val current=checkNotNull(session);check(current.principal.multiTeamProtocol==1 && !current.offline)
        val value=code.trim().also { require(it.length in 1..128) };val key="apply/$value"
        val body=pendingTeams.getOrPut(key) { JSONObject().put("requestId",java.util.UUID.randomUUID().toString()).put("code",value) }
        call(current,"/v1/me/teams/apply",body);loadDetailsWhenOnline(current);pendingTeams.remove(key)
        mutable.update { it.copy(message="申请已提交，需该团队管理员审核；已有团队和个人曲库保持") }
    }

    fun open() = operation {
        if (session != null) {
            verifySession()
            syncScores(checkNotNull(session))
            loadDetailsWhenOnline(checkNotNull(session))
            return@operation
        }
        val restored = withContext(Dispatchers.IO) { gateway.restore() }
        session = restored
        mutable.value = TeamAccountState(server = restored?.server ?: gateway.server, principal = restored?.principal, busy = true,
            offline = restored?.offline == true, verifiedUntil = restored?.expiresAt ?: 0, libraryBlocked = restored != null)
        if (restored != null) {
            activateScores(restored)
            syncScores(restored)
            loadDetailsWhenOnline(restored)
        }
    }
    fun login(username: String, password: String, name: String? = null,
        invitation: String? = null, consent: Boolean = false) = operation {
        runs?.end()
        check(session == null)
        val loggedIn = withContext(Dispatchers.IO) { gateway.signIn(gateway.server, username, password, name, invitation, consent) }
        beginSession(loggedIn)
    }
    fun signup(username: String, password: String, name: String, consent: Boolean) = operation {
        check(session == null)
        runs?.end()
        val loggedIn = try { withContext(Dispatchers.IO) { gateway.signUp(username, password, name, consent) } }
            catch(error:java.io.IOException) { if(error is AccountRejected)throw error else throw SignupUnconfirmed(error) }
        check(!loggedIn.principal.joined && loggedIn.principal.membershipSupported)
        beginSession(loggedIn,registered=true)
    }
    private suspend fun beginSession(loggedIn: TeamSession,registered:Boolean=false) {
        session = loggedIn
        pendingApplication = null; pendingReviews.clear();pendingTeams.clear()
        mutable.value = TeamAccountState(server = loggedIn.server, principal = loggedIn.principal, busy = true,
            verifiedUntil = loggedIn.expiresAt, message = if(registered) "注册成功，已登录。个人空间由你管理，可以创建团队或申请加入其他团队。"
                else if(!loggedIn.principal.joined && loggedIn.principal.multiTeamProtocol==1) "登录成功。个人空间由你管理，可以创建团队或申请加入其他团队。"
                else if (loggedIn.principal.joined) "登录成功，设备已绑定"
                else "账号已登录，请通过乐团码申请加入并等待管理员审核")
        activateScores(loggedIn)
        syncScores(loggedIn)
        loadDetailsWhenOnline(loggedIn)
    }
    fun refresh() = operation {
        verifySession()
        mutable.value = mutable.value.copy(message = if (session!!.offline) "当前离线，使用上次已核验的账号" else "账号状态已更新")
        syncScores(checkNotNull(session))
        loadDetailsWhenOnline(checkNotNull(session))
    }
    fun sync() {
        if (session == null) return
        operation {
            verifySession(); syncScores(checkNotNull(session))
            if (session?.principal?.joined == false) loadDetailsWhenOnline(checkNotNull(session))
        }
    }
    private fun downloadOwner():String? = mutable.value.principal?.takeIf { it.joined }?.let {
        com.ensemble.scorelibrary.sync.teamId("${mutable.value.server}/${it.teamId}/${it.accountId}")
    }
    fun pauseDownloads(expectedKey:String?=null) {
        val key=downloadOwner() ?: return
        if(expectedKey!=null && key!=expectedKey)return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { scores?.pauseDownloads(key) }
                if(downloadOwner()==key)mutable.update { it.copy(syncMessage="已请求暂停下载；已验证缓存仍可阅读") }
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { if(downloadOwner()==key)mutable.update { it.copy(syncMessage="暂停设置未能保存，请重试") } }
        }
    }
    fun resumeDownloads(expectedKey:String?=null) {
        val key=downloadOwner() ?: return
        if(expectedKey!=null && key!=expectedKey)return
        operation {
            verifySession()
            val current=checkNotNull(session)
            check(downloadOwner()==key)
            if (!current.principal.joined) { syncScores(current); return@operation }
            if(current.offline) {
                mutable.update { it.copy(syncMessage="请联网核验后继续下载；已有缓存可离线阅读") }
                return@operation
            }
            if(mutable.value.libraryMember==null)activateScores(current)
            withContext(Dispatchers.IO) { scores?.resumeDownloads(key) }
            syncScores(current)
        }
    }
    private suspend fun verifySession() {
        val verified = try { withContext(Dispatchers.IO) { gateway.verify(checkNotNull(session)) } }
            catch (error: AccountRejected) {
                if (error.status == 403) throw AccountRejected(401, "session_invalid") else throw error
            }
        session = verified
        mutable.update { it.copy(principal = verified.principal, offline = verified.offline, verifiedUntil = verified.expiresAt,
            devices = if (verified.offline) emptyList() else it.devices, members = if (verified.offline) emptyList() else it.members) }
        if (!verified.principal.joined) blockLibrary(verified)
    }
    private suspend fun activateScores(current: TeamSession) {
        if (!current.principal.joined) { blockLibrary(current); return }
        val member = scores?.activate(current) ?: return
        mutable.value = mutable.value.copy(libraryMember = member)
    }
    private suspend fun syncScores(current: TeamSession) {
        if (!current.principal.joined) { blockLibrary(current); armExpiry(current); return }
        val sync = scores ?: return
        if (mutable.value.libraryMember == null) activateScores(current)
        try {
            val count = sync.sync(current) { done, total ->
                mutable.update { it.copy(syncMessage = "正在接收乐谱 $done / $total") }
            }
            mutable.value = mutable.value.copy(syncMessage = if (current.offline) "离线可读 $count 份已缓存乐谱；笔迹先保存在本机，联网核验后同步"
                else if (count == 0) "暂无分配给你的乐谱，请联系管理员" else "已同步 $count 份授权乐谱")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (error is AccountRejected && error.status == 401) throw error
            if (error is com.ensemble.scorelibrary.sync.TeamCleanupFailure) {
                sync.deactivate()
                mutable.update { it.copy(libraryMember=null,libraryBlocked=true,syncMessage=error.message.orEmpty()) }
                throw error
            }
            if (error is OfflineUnavailable) {
                sync.deactivate()
                mutable.update { it.copy(libraryMember = null, syncMessage = error.message.orEmpty()) }
                throw error
            }
            mutable.value = mutable.value.copy(syncMessage = if (error is com.ensemble.scorelibrary.sync.TeamSyncFailure)
                error.message.orEmpty() else "同步未完成，请检查网络后重试；已验证的本机乐谱可以继续阅读")
        } finally { armExpiry(current) }
    }
    private fun blockLibrary(current: TeamSession) {
        runs?.end(); scores?.deactivate()
        mutable.update { it.copy(libraryMember = null, libraryBlocked = true, members = emptyList(),
            joinCode = null, joinCodeRevision = null, joinRequests = emptyList(), joinRequestTotal = 0,
            syncMessage = when (current.principal.membership) {
                "pending" -> "入团申请正在等待管理员审核"
                "rejected" -> "申请未通过，可向管理员确认乐团码后重新申请"
                else -> "尚未加入乐团，审核通过后由管理员分配乐谱"
            }) }
    }
    private fun armExpiry(current: TeamSession) {
        val until = minOf(current.expiresAt, scores?.offlineDeadline ?: current.expiresAt)
        if (until > System.currentTimeMillis() / 1000 + OFFLINE_SECONDS + CLOCK_SKEW_SECONDS) return
        if (until == deadline && expiryToken == current.token && expiry?.isActive == true) return
        deadline = until
        expiryToken = current.token
        expiry?.cancel()
        expiry = viewModelScope.launch {
            delay((until * 1000 - System.currentTimeMillis()).coerceAtLeast(0))
            if (deadline == until && session?.token == current.token) {
                runs?.end()
                scores?.deactivate()
                mutable.update { it.copy(libraryMember = null, syncMessage = "离线核验期限已到，请联网重新核验") }
            }
        }
    }
    private suspend fun loadDetailsWhenOnline(current: TeamSession) {
        if (current.offline) return
        try { loadDetails(current) } catch (error: Exception) {
            if (error is CancellationException || !temporarilyUnavailable(error)) throw error
            mutable.update { it.copy(message = "账号详情暂时不可达，已核验的缓存可继续阅读") }
        }
    }
    fun logout() { leaveAccount(false) }
    /** Explicit escape also works while login, activation or a download is waiting. */
    fun leaveAccount() { leaveAccount(true) }
    private fun leaveAccount(returnToLocal:Boolean) {
        if (exiting) return
        exiting=true
        mutable.update { it.copy(busy=true) }
        val previous = activeOperation
        previous?.cancel()
        leaving = viewModelScope.launch {
            try {
                previous?.join()
                operation(exitingOperation=true) { endSession(returnToLocal) }
                activeOperation?.join()
            } finally { exiting=false;mutable.update { it.copy(busy=false) } }
        }
    }
    private suspend fun endSession(returnToLocal: Boolean) {
        runs?.end()
        expiry?.cancel(); expiry = null
        val current = session
        // Drain the old operation before clearing durable identity or exposing local navigation.
        withContext(Dispatchers.IO) { gateway.clear() }
        scores?.deactivate()
        session = null
        pendingApplication = null; pendingReviews.clear();pendingTeams.clear()
        mutable.value = TeamAccountState(server = gateway.server, busy = true,
            libraryBlocked = !returnToLocal, message = "已清除本机登录，正在退出团队账号")
        val revoked = try {
            if (current != null) call(current, "/v1/auth/logout", JSONObject())
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        mutable.value = TeamAccountState(server = gateway.server, busy = true,
            libraryBlocked = !returnToLocal,
            message = if (revoked) "已退出团队账号" else "已清除本机登录；服务不可达，远端会话将在到期后失效")
    }
    fun changePassword(currentPassword: String, newPassword: String) = operation {
        runs?.end()
        call(checkNotNull(session), "/v1/auth/password", JSONObject().put("currentPassword", currentPassword).put("newPassword", newPassword))
        withContext(Dispatchers.IO) { gateway.clear() }
        scores?.deactivate()
        session = null
        mutable.value = TeamAccountState(server = gateway.server, busy = true, message = "密码已修改，所有设备需重新登录")
    }
    fun revokeDevice(id: String) = operation {
        val current = checkNotNull(session)
        call(current, "/v1/me/devices/revoke", JSONObject().put("deviceId", id))
        if (id == gateway.device) {
            runs?.end()
            withContext(Dispatchers.IO) { gateway.clear() }
            scores?.deactivate()
            session = null
            mutable.value = TeamAccountState(server = gateway.server, busy = true, message = "此设备已撤销")
        } else {
            loadDetails(current)
            mutable.value = mutable.value.copy(message = "设备已撤销，旧登录立即失效")
        }
    }
    fun invite() = operation {
        val result = call(checkNotNull(session), "/v1/admin/invitations", JSONObject())
        mutable.value = mutable.value.copy(invitation = result.getString("invitation"), message = "邀请码仅限一人使用，48 小时内有效")
    }
    fun hideInvitation() { mutable.value = mutable.value.copy(invitation = null, joinCode = null) }
    fun applyToTeam(code:String) {
        if(session?.principal?.multiTeamProtocol==1)applyOtherTeam(code) else applyToSingleTeam(code)
    }
    private fun applyToSingleTeam(code: String) = operation {
        val current = checkNotNull(session)
        check(!current.offline && !current.principal.joined && current.principal.membershipSupported)
        val membership = checkNotNull(mutable.value.membership)
        check(membership.state == current.principal.membership && membership.state in setOf("unjoined", "rejected"))
        val clean = code.trim().also { require(it.length in 1..128) }
        val body = pendingApplication?.takeIf { it.optString("code") == clean && it.optLong("baseRevision") == membership.revision }
            ?: JSONObject().put("requestId", java.util.UUID.randomUUID().toString()).put("baseRevision", membership.revision).put("code", clean)
                .also { pendingApplication = it }
        call(current, "/v1/me/membership", body)
        pendingApplication = null
        verifySession(); syncScores(checkNotNull(session)); loadDetailsWhenOnline(checkNotNull(session))
        mutable.update { it.copy(message = if (session?.principal?.joined == true) "入团申请已获批准，请等待管理员分配乐谱"
            else "入团申请已提交，等待管理员审核") }
    }
    fun rotateJoinCode(expectedRevision: Long) = operation {
        val current = checkNotNull(session)
        check(current.principal.joined && current.principal.role == "admin" && !current.offline)
        check(mutable.value.joinCodeRevision == expectedRevision)
        val result = call(current, "/v1/admin/join-code", JSONObject().put("baseRevision", expectedRevision))
        val code = result.getString("code").also {
            require(it.matches(Regex("[A-Za-z0-9]{6}")) || it.matches(Regex("[A-Za-z0-9_-]{32}")))
        }
        val revision = result.getLong("revision").also { require(it == expectedRevision + 1) }
        mutable.update { it.copy(joinCode = code, joinCodeRevision = revision, joinCodeConfigured = true,
            message = "乐团码已生成，请复制后交给申请人；更换后旧码失效，已有申请仍待审核") }
    }
    fun reviewJoin(request: TeamJoinRequest, approved: Boolean) = operation {
        val current = checkNotNull(session)
        check(current.principal.joined && current.principal.role == "admin" && !current.offline)
        check(mutable.value.joinRequests.any { it == request })
        val key = "${request.id}/${request.revision}/$approved"
        val body = pendingReviews.getOrPut(key) { JSONObject().put("requestId", java.util.UUID.randomUUID().toString())
            .put("baseRevision", request.revision).put("accountId", request.id).put("approved", approved) }
        call(current, "/v1/admin/join-requests/review", body)
        pendingReviews.remove(key)
        loadDetailsWhenOnline(current)
        mutable.update { it.copy(message = if (approved) "已批准入团，请在乐谱管理中为新成员分配声部" else "已拒绝申请；申请人可重新申请") }
    }
    fun memberStatus(id: String, active: Boolean) = operation {
        val current = checkNotNull(session)
        call(current, "/v1/admin/members/status", JSONObject().put("accountId", id).put("active", active))
        loadDetails(current)
        mutable.value = mutable.value.copy(message = if (active) "本团队成员已启用，请刷新团队状态" else "成员已在本团队停用；其他团队和个人文件保持")
    }
    fun consent() = operation {
        val current = checkNotNull(session)
        call(current, "/v1/me/backup-consent", JSONObject().put("accepted", true))
        session = current.copy(principal = current.principal.copy(backupConsent = true))
        mutable.value = mutable.value.copy(principal = session!!.principal, message = "已确认；个人批注按账号自动备份，团队管理员可只读查看")
        syncScores(checkNotNull(session))
    }
    private suspend fun loadDetails(current: TeamSession) {
        val rows = call(current, "/v1/me/devices").getJSONArray("devices")
        require(rows.length() <= 1000)
        val devices = (0 until rows.length()).map {
            val row = rows.getJSONObject(it)
            TeamDevice(row.getString("id"), row.getString("name"), row.getBoolean("revoked"), row.getBoolean("current"))
        }
        val members = if (current.principal.joined && current.principal.role == "admin") {
            val people = call(current, "/v1/admin/members").getJSONArray("members")
            require(people.length() <= 10000)
            (0 until people.length()).map {
                val row = people.getJSONObject(it)
                TeamMember(row.getString("accountId"), row.getString("displayName"), row.getString("username"), row.getBoolean("active"), row.getString("role") == "admin")
            }
        } else emptyList()
        val teams=if(current.principal.multiTeamProtocol==1)parseAccountTeams(call(current,"/v1/me/teams"),current) else emptyList()
        mutable.value = mutable.value.copy(devices = devices, members = members,teams=teams)
        if (current.principal.membershipSupported) {
            val membership = TeamMembership.parse(call(current, "/v1/me/membership"))
            mutable.update { it.copy(membership = membership) }
            if (current.principal.joined && current.principal.role == "admin") {
                val code = call(current, "/v1/admin/join-code")
                val revision = code.getLong("revision").also { require(it >= 0) }
                val queue = call(current, "/v1/admin/join-requests")
                val rows = queue.getJSONArray("requests").also { require(it.length() <= 200) }
                val requests = (0 until rows.length()).map { index ->
                    val row = rows.getJSONObject(index)
                    TeamJoinRequest(row.getString("accountId").also { require(java.util.UUID.fromString(it).toString() == it) },
                        row.getString("username").also { require(it.length in 3..64) },
                        row.getString("displayName").also { require(it.length in 1..100) },
                        row.getLong("revision").also { require(it > 0) })
                }
                require(requests.map { it.id }.distinct().size == requests.size)
                val total = queue.getInt("total").also { require(it >= requests.size) }
                mutable.update { it.copy(joinCodeRevision = revision, joinCodeConfigured = code.getBoolean("configured"),
                    joinCode = it.joinCode.takeIf { _ -> it.joinCodeRevision == revision }, joinRequests = requests, joinRequestTotal = total) }
            }
        }
    }
    private suspend fun call(current: TeamSession, path: String, body: JSONObject? = null) =
        withContext(Dispatchers.IO) { gateway.request(current, path, body) }

    private fun operation(exitingOperation:Boolean=false,block: suspend () -> Unit) {
        if (!exitingOperation && (mutable.value.busy || exiting)) return
        mutable.value = mutable.value.copy(busy = true, message = "")
        activeOperation = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (error is AccountRejected && error.status == 401) {
                    withContext(Dispatchers.IO) { gateway.clear() }
                    scores?.deactivate()
                    session = null
                    mutable.value = TeamAccountState(server = gateway.server, busy = true)
                }
                mutable.value = mutable.value.copy(message = accountError(error))
            } finally { if(!exiting)mutable.value = mutable.value.copy(busy = false) }
        }
    }
}
