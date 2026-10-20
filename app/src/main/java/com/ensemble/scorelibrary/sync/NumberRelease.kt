package com.ensemble.scorelibrary.sync

import com.ensemble.scorelibrary.domain.ScoreNumber
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ReleasableNumber(val sequence:String,val parts:Int)
data class NumberReleaseInventory(val key:String,val storage:String,val account:String,val revision:Long,val eligible:List<ReleasableNumber>) {
    companion object {
        internal fun parse(row:JSONObject,c:TeamCollection):NumberReleaseInventory {
            val p=checkNotNull(c.session).principal
            require(row.get("protocolVersion")==1 && row.get("numberReleaseReady")==true &&
                row.get("teamId")==p.teamId && row.get("accountId")==p.accountId)
            val storage=row.getString("storageId");require(UUID.fromString(storage).toString()==storage)
            val revision=integer(row,"revision")
            val list=row.getJSONArray("eligible");require(list.length()<=100000)
            val values=(0 until list.length()).map { i ->
                val item=list.getJSONObject(i);val sequence=item.get("sequence").also { require(it is String) } as String
                ScoreNumber.sequence(sequence)
                val parts=integer(item,"deletedParts");require(parts in 1..100000)
                integer(item,"latestDeletedAt")
                ReleasableNumber(sequence,parts.toInt())
            }
            require(values.map { it.sequence }.distinct().size==values.size && values==values.sortedBy { ScoreNumber.sequence(it.sequence) })
            return NumberReleaseInventory(c.key,storage,p.accountId,revision,values)
        }
    }
}

data class NumberReleasePreview(val key:String,val storage:String,val account:String,val revision:Long,
    val sequences:Set<String>,val parts:Long,val digest:String,val request:String=UUID.randomUUID().toString()) {
    internal fun body()=JSONObject().put("sequences",JSONArray(sequences.sortedBy { ScoreNumber.sequence(it) }))
        .put("expectedRevision",revision).put("scopeDigest",digest).put("requestId",request).put("confirmed",true)
    companion object {
        internal fun parse(row:JSONObject,inventory:NumberReleaseInventory,wanted:Set<String>):NumberReleasePreview {
            require(wanted.size in 1..200 && inventory.eligible.map { it.sequence }.containsAll(wanted))
            require(row.get("protocolVersion")==1 && row.get("storageId")==inventory.storage && row.get("accountId")==inventory.account &&
                integer(row,"revision")==inventory.revision)
            val actual=sequences(row.getJSONArray("sequences"));require(actual.toSet()==wanted && actual.size==wanted.size)
            val parts=integer(row,"deletedParts")
            require(parts==inventory.eligible.filter { it.sequence in wanted }.sumOf { it.parts.toLong() })
            val digest=row.getString("scopeDigest");require(digest.matches(Regex("[a-f0-9]{64}")))
            return NumberReleasePreview(inventory.key,inventory.storage,inventory.account,inventory.revision,wanted.toSet(),parts,digest)
        }
    }
}
internal fun integer(row:JSONObject,key:String):Long {
    val value=row.get(key);require(value is Int || value is Long)
    return (value as Number).toLong().also { require(it>=0) }
}
internal fun sequences(values:JSONArray):List<String> {
    require(values.length() in 1..200)
    return (0 until values.length()).map { i -> (values.get(i).also { require(it is String) } as String).also { ScoreNumber.sequence(it) } }
        .also { require(it.distinct().size==it.size && it==it.sortedBy { value -> ScoreNumber.sequence(value) }) }
}
