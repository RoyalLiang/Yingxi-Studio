package io.localplayer.app
import androidx.test.platform.app.InstrumentationRegistry
import io.localplayer.core.*
import org.junit.Test
/** Opt-in preparation only: both loopback servers expose r8-smoke/r8-{avc,hevc}.mp4.
 * Keep these two clips apart from the large-file/random-access fixture directory.
 * Install debug, run persist, install the optimized candidate with -r, then run
 * ReleaseLaunchSmoke. Restore debug and run remove to clear only these sources.
 */
class OptimizedFixtureSetupTest {
    @Test fun removeSyntheticSources(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=SourceStore(context)
        store.delete("r8-smoke-smb");store.delete("r8-smoke-sftp")
        check(context.getSharedPreferences("sources-v1", android.content.Context.MODE_PRIVATE).edit().commit())
        check(context.getSharedPreferences("secrets-v1", android.content.Context.MODE_PRIVATE).edit().commit())
    }
    @Test fun persistSyntheticSourcesForProductionSmoke(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=SourceStore(context)
        val password=TestFixtures.password
        val unknown=Source(id="r8-smoke-sftp",label="R8 SFTP",protocol=Protocol.SFTP,host="10.0.2.2",port=TestFixtures.sftpPort,root="/",username=TestFixtures.username,initialPath="r8-smoke")
        val fingerprint=try{SftpConnector().connect(unknown,Credentials(password)).close();error("Expected host key confirmation")}catch(e:HostKeyRequired){e.fingerprint}
        store.save(unknown.copy(trustedKey=fingerprint,trustedAuthority=unknown.authority),password,true)
        store.save(Source(id="r8-smoke-smb",label="R8 SMB",protocol=Protocol.SMB,host="10.0.2.2",port=TestFixtures.smbPort,root="media",username=TestFixtures.username,initialPath="r8-smoke"),password,true)
        check(store.load().map { it.id }.containsAll(listOf("r8-smoke-smb", "r8-smoke-sftp")))
        // The next step replaces this APK immediately; flush apply() before the
        // instrumentation process exits so the production build sees the fixtures.
        check(context.getSharedPreferences("sources-v1", android.content.Context.MODE_PRIVATE).edit().commit())
        check(context.getSharedPreferences("secrets-v1", android.content.Context.MODE_PRIVATE).edit().commit())
    }
}
