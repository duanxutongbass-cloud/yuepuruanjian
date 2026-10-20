package com.ensemble.scorelibrary.sync

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PermanentPreview(val accountKey:String,val revision:Long,val digest:String,val artifacts:Set<String>,
    val titles:List<String>,val bytes:Long,val personal:Long,val shared:Long,val history:Long,val programs:List<String>,
    val frozen:Int,val folders:Int,val request:String=UUID.randomUUID().toString()) {
    internal fun body()=JSONObject().put("artifactIds",JSONArray(artifacts.sorted())).put("expectedRevision",revision)
        .put("scopeDigest",digest).put("requestId",request).put("confirmed",true)
    companion object {
        internal fun parse(row:JSONObject,key:String,ids:Set<String>,revision:Long):PermanentPreview {
            require(row.get("permanentDeletionReady")==true && row.getLong("revision")==revision)
            fun count(name:String):Long {
                val n=row.get(name);require(n is Int || n is Long)
                return (n as Number).toLong().also { require(it>=0) }
            }
            val digest=row.getString("scopeDigest");require(digest.matches(Regex("[a-f0-9]{64}")))
            val parts=row.getJSONArray("parts");require(parts.length()==ids.size && count("fileCount")==ids.size.toLong())
            val actual=(0 until parts.length()).map { parts.getJSONObject(it).getString("artifactId") }
            require(actual.toSet()==ids && actual.distinct().size==actual.size && actual.all { UUID.fromString(it).toString()==it })
            val titles=(0 until parts.length()).map { parts.getJSONObject(it).let { p ->
                "${p.getString("scoreNumber")} · ${p.getString("title")} · ${p.getString("partName")}".also { require(it.length<=610) }
            } }
            val programmes=row.getJSONArray("programs");require(programmes.length()<=1000)
            val names=(0 until programmes.length()).map { programmes.getJSONObject(it).getString("title").also { require(it.length<=100) } }
            val freezes=row.getJSONArray("freezeIds");require(freezes.length()<=1000)
            val folders=row.getJSONArray("folderAccounts");require(folders.length()<=10000)
            return PermanentPreview(key,revision,digest,ids,titles,count("totalBytes"),count("personalPages"),count("sharedPages"),
                count("historyRecords"),names,freezes.length(),folders.length())
        }
    }
}
