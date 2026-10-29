package io.localplayer.app
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Run interrupt then recover in separate instrumentation processes for each checkpoint. */
class MigrationProcessRecoveryTest {
    private val stage get()=InstrumentationRegistry.getArguments().getString("stage")?:error("Explicit stage required")
    private fun context()=IsolatedMediaContext(InstrumentationRegistry.getInstrumentation().targetContext,"process-recovery-$stage-")
    @Test fun interrupt(){
        require(stage in setOf("snapshot","validated","imported","committed"))
        val c=context();val legacy=LegacyHistoryStore(c);val ref=MediaRef("recovery:one","old.mp4","旧片箱",sizeBytes=100)
        legacy.update(ref,legacy.token(ref.key),42000,120000,false)
        MediaDataMigration.session(c).apply{migrationProbe={if(it==stage){if(it=="committed")File(c.noBackupFilesDir,"identity").writeText(HistoryStore(c).entries().single().media.mediaId!!);File(c.noBackupFilesDir,"interrupted").outputStream().use{stream->stream.write(it.toByteArray());stream.fd.sync()};android.os.Process.killProcess(android.os.Process.myPid())}};migrate()}
        fail("Injected process death did not happen")
    }
    @Test fun recover(){
        val c=context();try{
            assertEquals(stage,File(c.noBackupFilesDir,"interrupted").readText())
            val session=MediaDataMigration.session(c);assertEquals("Committed",session.migrate().state)
            val entries=HistoryStore(c).entries();assertEquals(1,entries.size);assertEquals(42000L,entries.single().positionMs);assertNotNull(entries.single().media.mediaId)
            val id=entries.single().media.mediaId;if(stage=="committed")assertEquals(File(c.noBackupFilesDir,"identity").readText(),id);assertEquals("Committed",session.migrate().state);assertEquals(id,HistoryStore(c).entries().single().media.mediaId)
        }finally{c.dispose()}
    }
}
