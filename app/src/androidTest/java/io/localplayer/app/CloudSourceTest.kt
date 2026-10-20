package io.localplayer.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CloudSourceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun startPicker() {
        ui.onNodeWithText("来源").performClick();ui.onNodeWithContentDescription("添加来源").performClick()
        ui.onNodeWithText("WebDAV").performClick();ui.onNodeWithTag("cloud-provider-picker").assertIsDisplayed()
    }
    private fun capture(name:String) {
        ui.waitForIdle();val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {java.io.File(context.getExternalFilesDir(null),"cloud-$name.png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}finally{image.recycle()}
    }
    @Test fun providerSelectionAndManualWebDavStayDistinct() {
        startPicker();ui.onNodeWithText("阿里云盘").assertIsDisplayed();ui.onNodeWithText("百度网盘").assertIsDisplayed()
        ui.onNode(hasText("WebDAV 地址") and hasSetTextAction()).assertDoesNotExist();capture("picker")
        ui.onNodeWithTag("manual-webdav").performClick()
        ui.onNode(hasText("WebDAV 地址") and hasSetTextAction()).assertIsDisplayed()
        assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        ui.waitUntil(5000){runCatching{ui.onNodeWithTag("cloud-provider-picker").assertIsDisplayed()}.isSuccess}
        ui.onNodeWithText("SMB").performClick()
        ui.onNode(hasText("服务器地址 / SMB 链接") and hasSetTextAction()).assertIsDisplayed()
    }
    @Test fun absentAppsShowExplicitPromptWithoutCreatingSources() {
        val apps=AndroidCloudApps(context);CloudProvider.entries.forEach{assertEquals(CloudAppStatus.NOT_INSTALLED,apps.status(it))}
        startPicker();val vm=ViewModelProvider(ui.activity)[LibraryViewModel::class.java]
        var before=0;ui.runOnIdle{before=vm.sources.size}
        CloudProvider.entries.forEach { p->
            ui.onNodeWithTag("cloud-open-${p.name}").performClick();ui.onNodeWithText("未安装${p.title}").assertIsDisplayed()
            ui.onNodeWithText("官方安装入口").assertIsDisplayed();capture("missing-${p.name}")
            ui.onNodeWithText("知道了").performClick();ui.runOnIdle{assertEquals(before,vm.sources.size)}
        }
    }
    @Test fun installedEntryRoutesOnlyTheChosenApp() {
        val opened=mutableListOf<CloudProvider>()
        val gateway=object:CloudAppGateway {
            override fun status(provider:CloudProvider)=CloudAppStatus.AVAILABLE
            override fun open(provider:CloudProvider):CloudLaunchResult{opened+=provider;return CloudLaunchResult.OPENED}
        }
        ui.activity.setContent{PlayerTheme{Column{CloudProviderPicker(gateway)}}}
        CloudProvider.entries.forEach{ui.onNodeWithTag("cloud-open-${it.name}").performClick()}
        ui.runOnIdle{assertEquals(CloudProvider.entries.toList(),opened)}
        ui.onNodeWithText("未安装阿里云盘").assertDoesNotExist()
    }
    @Test fun unavailableAppIsNotReportedAsMissing() {
        val gateway=object:CloudAppGateway {
            override fun status(provider:CloudProvider)=CloudAppStatus.UNAVAILABLE
            override fun open(provider:CloudProvider)=CloudLaunchResult.UNAVAILABLE
        }
        ui.activity.setContent{PlayerTheme{Column{CloudProviderPicker(gateway)}}}
        ui.onNodeWithTag("cloud-open-ALIYUNPAN").performClick()
        ui.onNodeWithText("阿里云盘暂不可用").assertIsDisplayed();ui.onNodeWithText("未安装阿里云盘").assertDoesNotExist()
    }
    @Test fun endUsersNeverSeeDeveloperCredentials() {
        startPicker();ui.onNodeWithText("接入配置").assertDoesNotExist()
        ui.onNode(hasText("AppID",substring=true) and hasSetTextAction()).assertDoesNotExist()
        ui.onNode(hasText("AppKey",substring=true) and hasSetTextAction()).assertDoesNotExist()
        ui.onNodeWithText("阿里云盘仅申请读取权限；百度正式接入需企业主体。").assertIsDisplayed()
    }
}
