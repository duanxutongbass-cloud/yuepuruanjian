package com.ensemble.scorelibrary.domain.sync

import com.ensemble.scorelibrary.sync.RemoteManifest
import com.ensemble.scorelibrary.sync.TestHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteManifestTest {
    private val id = "00000000-0000-0000-0000-000000000001"
    private val login = TestHttpClient.Login("temporary", id, id, id, "Synthetic")
    private fun manifest() = JSONObject("""{"protocolVersion":1,"revision":1,"operationId":"run/1","principal":{"teamId":"$id","accountId":"$id","memberId":"$id"},"artifacts":[{"artifactId":"$id","sourceVersionId":"$id","scorePdfId":"$id","pieceId":"$id","partId":"$id","definitionId":"$id","title":"Synthetic","partName":"Violin","versionNumber":1,"hash":"${"a".repeat(64)}","size":100,"pageMap":[{"sourcePage":0,"transform":"identity"}],"independentSource":true}]}""")
    private fun rejected(json: JSONObject) {
        try { RemoteManifest.parse(json, login); fail("Untrusted projection must be rejected") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun otherAccountOrTeamCannotBecomeCurrentProjection() {
        for (field in listOf("teamId", "accountId", "memberId")) {
            val m = manifest(); m.getJSONObject("principal").put(field, "other"); rejected(m)
        }
    }
    @Test fun derivedPagesOrNonIdentityMappingCannotMasqueradeAsOriginalVersion() {
        val valid = RemoteManifest.parse(manifest(), login)
        assertEquals(listOf(SyncPage(0, "identity")), valid.scores.single().artifact.pageMap)
        for (field in listOf("sourcePage", "transform")) {
            val m = manifest()
            m.getJSONArray("artifacts").getJSONObject(0).getJSONArray("pageMap").getJSONObject(0).put(field, if (field == "sourcePage") 4 else "rotate90")
            rejected(m)
        }
        val m = manifest(); m.getJSONArray("artifacts").getJSONObject(0).put("independentSource", false); rejected(m)
    }
    @Test fun pathTraversalOversizedOrMalformedResourceIsRejected() {
        for ((field, value) in listOf("artifactId" to "../other", "size" to 32_000_000, "hash" to "unverified")) {
            val m = manifest(); m.getJSONArray("artifacts").getJSONObject(0).put(field, value); rejected(m)
        }
        val m = manifest(); val arr = m.getJSONArray("artifacts"); arr.put(arr.getJSONObject(0)); rejected(m)
    }
}
