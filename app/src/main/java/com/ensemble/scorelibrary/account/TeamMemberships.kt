package com.ensemble.scorelibrary.account

import org.json.JSONObject
import java.util.UUID

data class AccountTeam(val id:String,val name:String,val role:String,val membership:String,val active:Boolean,val revision:Long) {
    val joined:Boolean get()=active && membership=="joined"
}

internal fun parseAccountTeams(json:JSONObject,session:TeamSession):List<AccountTeam> {
    require(json.get("protocolVersion")==1 && json.getString("accountId")==session.principal.accountId &&
        json.getString("currentTeamId")==session.principal.teamId) { "团队列表身份不匹配" }
    val rows=json.getJSONArray("teams");require(rows.length()<=1000)
    val result=(0 until rows.length()).map { index ->
        val row=rows.getJSONObject(index)
        val id=row.getString("teamId").also { require(UUID.fromString(it).toString()==it) }
        val name=row.getString("teamName").also { require(it.length in 1..100 && it.none(Char::isISOControl)) }
        val role=row.getString("role").also { require(it in setOf("admin","member")) }
        val membership=row.getString("membership").also { require(it in MEMBERSHIP_STATES) }
        val active=row.get("active").also { require(it is Boolean) } as Boolean
        val revision=row.getLong("revision").also { require(it in 0 until 9_007_199_254_740_992L) }
        require(role!="admin" || membership=="joined")
        AccountTeam(id,name,role,membership,active,revision)
    }
    require(result.map { it.id }.distinct().size==result.size)
    return result
}
