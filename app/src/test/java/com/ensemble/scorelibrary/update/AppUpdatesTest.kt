package com.ensemble.scorelibrary.update

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppUpdatesTest {
    private val metadata = JSONObject().put("available",true).put("packageName","com.ensemble.scorelibrary")
        .put("versionCode",14).put("versionName","0.10.2").put("size",20000000).put("sha256","a".repeat(64)).put("notes","Update")
    @Test fun onlyNewerSameAppIsOffered() {
        assertEquals(14L,AppRelease.parse(metadata,"com.ensemble.scorelibrary",13)!!.code)
        assertNull(AppRelease.parse(metadata,"com.ensemble.scorelibrary",14))
        assertNull(AppRelease.parse(JSONObject().put("available",false),"com.ensemble.scorelibrary",13))
        assertTrue(runCatching{AppRelease.parse(metadata,"other.package",13)}.isFailure)
    }
    @Test fun malformedOrUnboundedReleaseIsRejected() {
        for ((key,value) in listOf("size" to 200000000,"size" to -1,"sha256" to "../not-a-hash","versionName" to "x".repeat(100))) {
            val changed=JSONObject(metadata.toString()).put(key,value)
            assertTrue(key,runCatching{AppRelease.parse(changed,"com.ensemble.scorelibrary",13)}.isFailure)
        }
    }
    @Test fun signerPackageAndVersionMustAllMatch() {
        fun allowed(name:String="app",installed:Long=13,archive:Long=14,release:Long=14,
            old:Set<String> = setOf("signer"),fresh:Set<String> = setOf("signer")) =
            updateIdentityMatches("app",name,installed,archive,release,old,fresh)
        assertTrue(allowed())
        assertFalse(allowed(name="other"))
        assertFalse(allowed(fresh=setOf("untrusted")))
        assertFalse(allowed(old=emptySet(),fresh=emptySet()))
        assertFalse(allowed(archive=13))
        assertFalse(allowed(release=15))
        assertFalse(allowed(installed=15))
    }
}
