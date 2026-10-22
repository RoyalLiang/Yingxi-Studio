package io.localplayer.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.localplayer.core.Protocol
import io.localplayer.core.Source
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class StationChapterRegressionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun diaryNameSitsBelowAvatarAndArchiveHasItsOwnRowAtLargeText() {
        ui.runOnUiThread { ui.activity.setContent { PlayerTheme {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.8f)) {
                Box(Modifier.width(320.dp)) { StationNotice("映汐放映站 · 值班札记","拾荒者日记 · 相伴第 1 天","灯亮着，片名留在罐身上。",onArchives={}) }
            }
        } } }
        val avatar=ui.onNodeWithTag("home-admin-avatar",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val name=ui.onNodeWithTag("home-admin-title",useUnmergedTree=true).assertTextEquals("阿汐").fetchSemanticsNode().boundsInRoot
        val body=ui.onNodeWithTag("home-admin-quote",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val button=ui.onNodeWithTag("home-archives").assertHasClickAction().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(name.top>=avatar.bottom)
        assertTrue(button.top>=body.bottom)
        assertTrue(button.width>avatar.width*3)
        ui.onNodeWithText("阿汐 · 据点管理员").assertDoesNotExist()
    }

    @Test fun chapterAndLetterPositionsSurviveNewReaderInstancesIndependently() {
        val name="chapter-test-${UUID.randomUUID()}"
        try {
            val chapters=StationWorld.load(context).chapters
            val first=chapters[0];val second=chapters[1]
            ChapterReading(context,name).select(second,second.letters[1])
            ChapterReading(context,name).select(first,first.letters[0])
            val restored=ChapterReading(context,name)
            assertEquals(first.id,restored.chapter(chapters).id)
            assertEquals(second.letters[1].id,restored.letter(second).id)
            restored.select(second,restored.letter(second))
            assertEquals(second.id,ChapterReading(context,name).chapter(chapters).id)
            context.getSharedPreferences(name,0).edit().putString("chapter","removed").putString("letter:${first.id}","removed").commit()
            assertEquals(first.id,ChapterReading(context,name).chapter(chapters).id)
            assertEquals(first.letters[0].id,ChapterReading(context,name).letter(first).id)
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun secondChapterIsAvailableWithoutViewingAndKeepsEachChapterPosition() {
        context.getSharedPreferences("station-reading-v1",0).edit().clear().commit()
        ui.runOnUiThread { ui.activity.setContent { PlayerTheme { StationWorldPage(embedded=true){} } } }
        ui.onNodeWithTag("world-scroll").performScrollToNode(hasTestTag("world-chapter:chapter_02"))
        ui.onNodeWithTag("world-chapter:chapter_02").performClick().assertIsSelected()
        ui.onNodeWithTag("world-scroll").performScrollToNode(hasTestTag("chapter-next"))
        ui.onNodeWithTag("chapter-next").performClick()
        ui.onNodeWithTag("chapter-position").assertTextEquals("第 2 / 3 封")
        ui.onNodeWithTag("world-scroll").performScrollToNode(hasTestTag("world-chapter:chapter_01"))
        ui.onNodeWithTag("world-chapter:chapter_01").performClick()
        ui.onNodeWithTag("world-chapter:chapter_02").performClick()
        ui.onNodeWithTag("chapter-letter:ch02_register").assertExists()
        ui.runOnUiThread { ui.activity.setContent { PlayerTheme { StationWorldPage(embedded=true){} } } }
        ui.onNodeWithTag("chapter-letter:ch02_register").assertExists()
    }

    @Test fun connectionKindsKeepCloudAndLanSourcesDistinct() {
        assertEquals("据点",Source(label="LAN",protocol=Protocol.SMB,host="localhost").stationKind)
        assertEquals("据点",Source(label="SSH",protocol=Protocol.SFTP,host="localhost").stationKind)
        assertEquals("信号塔",Source(label="DAV",protocol=Protocol.WEBDAV,host="localhost").stationKind)
        assertEquals("信号塔",Source(label="Cloud",protocol=Protocol.ALIYUN,host="localhost").stationKind)
        assertEquals("映汐放映站",context.applicationInfo.loadLabel(context.packageManager).toString())
    }
}
