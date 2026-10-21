package io.localplayer.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ArchiveLayoutRegressionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val quote="哟，回来了。今天淘着啥好货了？"
    private fun layout(tag:String):TextLayoutResult {
        val values=mutableListOf<TextLayoutResult>()
        ui.onNodeWithTag(tag,useUnmergedTree=true).performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(values)}
        return values.single()
    }
    private fun capture(name:String){
        ui.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/yingxi-archive27-$name.png").use { p ->
            java.io.FileInputStream(p.fileDescriptor).use{it.readBytes()}
        }
    }
    @Test fun quoteFitsOneLineAcrossCompactWidthsWithoutEllipsis(){
        val width=mutableStateOf(240.dp)
        val line=mutableStateOf(quote)
        ui.runOnUiThread {ui.activity.setContent{PlayerTheme{Box(Modifier.fillMaxSize().background(Paper).padding(24.dp)){
            Box(Modifier.width(width.value)){AdministratorArchiveEntry(line.value,{})}
        }}}}
        listOf(quote,"阿汐的来信 · 人物与世界图谱").forEach { text ->
            listOf(240.dp,272.dp,312.dp,363.dp).forEach { value ->
                ui.runOnIdle{width.value=value;line.value=text}
                val result=layout("home-admin-quote")
                assertEquals("width=$value text=$text",1,result.lineCount)
                assertFalse("width=$value text=$text",result.hasVisualOverflow)
                assertFalse(result.isLineEllipsized(0))
            }
        }
        capture("quote")
    }
    @Test fun systemLargeTextRemainsReadableAndMayWrap(){
        ui.runOnUiThread {ui.activity.setContent{PlayerTheme{
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.8f)) {
                Box(Modifier.fillMaxSize().background(Paper).padding(24.dp)) {
                    Box(Modifier.width(240.dp)){AdministratorArchiveEntry(quote,{})}
                }
            }
        }}}
        val result=layout("home-admin-quote")
        assertTrue(result.lineCount>1);assertFalse(result.hasVisualOverflow)
        assertEquals(13f,result.layoutInput.style.fontSize.value,.01f)
        assertFalse(layout("home-admin-title").hasVisualOverflow)
        capture("large-text")
    }
    @Test fun selectionIsSquareAvatarOnlyAndProfileArtIsBounded(){
        ui.onNodeWithTag("home-archives").performClick()
        ui.onNodeWithTag("world-scroll").performScrollToNode(hasTestTag("world-person:axi"))
        val avatar=ui.onNodeWithTag("world-person:axi").assertIsSelected().getUnclippedBoundsInRoot()
        val name=ui.onNodeWithTag("world-person-name:axi",useUnmergedTree=true).getUnclippedBoundsInRoot()
        assertEquals(64f,(avatar.right-avatar.left).value,1f);assertEquals((avatar.right-avatar.left).value,(avatar.bottom-avatar.top).value,.1f)
        assertTrue(name.top>=avatar.bottom)
        ui.onNodeWithTag("world-person:chideng").performClick().assertIsSelected()
        ui.onNodeWithTag("world-person:axi").assertIsNotSelected()
        ui.onNodeWithTag("world-scroll").performScrollToNode(hasTestTag("world-profile-art"))
        val profile=ui.onNodeWithTag("world-profile-art",useUnmergedTree=true).getUnclippedBoundsInRoot()
        assertEquals(160f,(profile.right-profile.left).value,1f);assertEquals((profile.right-profile.left).value,(profile.bottom-profile.top).value,.1f)
        capture("selection")
    }
}
