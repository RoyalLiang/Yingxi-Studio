package io.localplayer.app

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import io.localplayer.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.UUID

class CloudDirectoryErrorTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val models=ViewModelStore()
    private var saved:Source?=null
    private lateinit var vm:LibraryViewModel

    private fun failDirectory(failure:IOException, replay:Boolean=false, newlyAuthorized:Boolean=false):String {
        val app=ui.activity.application
        val source=Source(id="test-cloud-vm-"+UUID.randomUUID(),label="目录错误验证",protocol=Protocol.ALIYUN,
            host="openapi.alipan.com",username="test-user",domain="test-drive")
        saved=source
        SourceStore(app).save(source,"synthetic local credential",true)
        val connector=object:Connector {
            override fun connect(source:Source,credentials:Credentials,cancellation:Cancellation):RemoteSession=object:RemoteSession {
                override fun list(path:String):List<Entry> { throw failure }
                override fun open(path:String):RemoteFile=throw AssertionError("Directory error test must not open media")
                override fun close() {}
            }
        }
        ui.runOnUiThread {
            vm=LibraryViewModel(app,connector);models.put("library",vm)
            val files=LocalFilesViewModel(app);models.put("files",files)
            ui.activity.setContent { PlayerTheme { AppShell(vm,files,{},{},{}) } }
            when {
                replay -> {
                    val media=MediaRef("test-history-"+source.id,"01.mp4",source.label,source.id,"video",location=mediaLocation(source))
                    vm.resume(HistoryEntry(media,1000,10000,System.currentTimeMillis(),false))
                }
                newlyAuthorized -> { vm.selectedGroup="favorites";vm.cloudConnected(source) }
                else -> vm.open(source)
            }
        }
        ui.waitUntil(10_000){vm.error!=null}
        ui.waitForIdle()
        assertNull(vm.source)
        assertTrue(SourceStore(app).load().any{it.id==source.id})
        return vm.error!!
    }

    @Test fun directoryApiFailureSurvivesViewModelAndDialog() {
        val failure=AliyunApiFailure(AliyunOperation.DIRECTORY,403,"PermissionDenied","safe-request-123")
        assertEquals(failure.message,failDirectory(failure))
        ui.onNodeWithText(failure.message!!).assertIsDisplayed()
    }
    @Test fun expiredAuthorizationIsNotReportedAsDirectoryPermissionFailure() {
        val failure=AliyunUnauthorized()
        assertEquals(failure.message,failDirectory(failure))
        ui.onNodeWithText(failure.message!!).assertIsDisplayed()
    }
    @Test fun untrustedTransportMessageCannotExposeCredentials() {
        val raw="Bearer synthetic-sensitive-value"
        val message=failDirectory(IOException(raw))
        assertFalse(message.contains(raw));assertTrue(message.contains("连接或读取失败"))
        ui.onNodeWithText(message).assertIsDisplayed()
        ui.onNodeWithText(raw).assertDoesNotExist()
    }
    @Test fun historyReplayAlsoRejectsUntrustedTransportMessages() {
        val raw="Bearer synthetic-sensitive-replay-value"
        val message=failDirectory(IOException(raw),replay=true)
        assertFalse(message.contains(raw));assertTrue(message.contains("连接或读取失败"))
        ui.onNodeWithText(raw).assertDoesNotExist()
    }
    @Test fun newlyAuthorizedAccountRemainsVisibleWhenInitialDirectoryFails() {
        failDirectory(AliyunApiFailure(AliyunOperation.DIRECTORY,403,"PermissionDenied"),newlyAuthorized=true)
        assertEquals("all",vm.selectedGroup)
        ui.onNodeWithText("避难所").performClick()
        ui.onNodeWithText("目录错误验证").assertIsDisplayed()
    }
    @After fun cleanup() {
        ui.runOnUiThread { models.clear() }
        saved?.let { SourceStore(ui.activity).delete(it.id) }
    }
}
