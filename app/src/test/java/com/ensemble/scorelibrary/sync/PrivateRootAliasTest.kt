package com.ensemble.scorelibrary.sync

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.account.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
class PrivateRootAliasTest {
    private val root=File(System.getProperty("java.io.tmpdir"),"private-alias-${UUID.randomUUID()}").apply { check(mkdirs()) }
    private val alias=File(root,".")
    private val key=SecretKeySpec(ByteArray(32){7},"AES")
    private val now=System.currentTimeMillis()/1000
    private val session=TeamSession(TEAM_SERVICE_URL,"synthetic-session",now+3600,TeamPrincipal(
        UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Team","player","Player","member",true),now,now,UUID.randomUUID().toString())
    @After fun cleanup() { root.deleteRecursively() }
    @Test fun trustedPrivateRootAliasCanRegisterSameAccountWithoutMovingItsFiles() {
        val index=TeamManagedIndex(alias,{key},{key})
        val owner=index.register(session)
        assertEquals(owner,TeamManagedIndex(root,{key},{key}).owners(session).single())
        assertTrue(File(root,"team-libraries/managed-identities.enc").isFile)
    }
    @Test fun trustedPrivateRootAliasCanReadDeletionCheckpoint() {
        val journal=TeamDeletionJournal(alias,session.server,session.principal.teamId){key}
        assertEquals(TeamDeletionCheckpoint(),journal.read())
    }
    @Test fun authorizedLibraryCanActivateWithTrustedPrivateRootAlias()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<Context>()
        val context=object:ContextWrapper(app) { override fun getNoBackupFilesDir()=alias }
        val scope=TeamLibraryScope(context)
        val offline=TeamOfflineStore(alias,{key},{now})
        val sync=TeamLibrarySync(scope,object:TeamScoreTransport {
            override suspend fun catalog(session:TeamSession)=TeamCatalog(0,emptyList())
            override suspend fun download(session:TeamSession,artifact:TeamArtifact,target:File)=error("No PDF expected")
        },object:TeamPdfVerifier { override fun verify(file:File,pages:Int)=error("No PDF expected") },offline=offline)
        try {
            val member=sync.activate(session)
            assertEquals(member,scope.active.value!!.member)
            assertEquals(0,sync.sync(session){_,_->})
            assertEquals(scope.active.value!!.directory.absoluteFile,scope.active.value!!.directory.canonicalFile)
            assertNotNull(offline.read(session))
        } finally { scope.active.value?.database?.close();scope.deactivate() }
    }
}
