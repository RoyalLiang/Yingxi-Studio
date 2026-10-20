package io.localplayer.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun threeTabsReachTheirActualPages(){
        ui.onNodeWithText("映汐播放器").assertIsDisplayed()
        ui.onNodeWithText("来源").performClick()
        ui.onNodeWithContentDescription("添加来源").assertIsDisplayed()
        ui.onAllNodesWithText("播放记录").filter(hasClickAction()).onFirst().performClick()
        ui.onNodeWithContentDescription("清空播放记录").assertIsDisplayed()
        ui.onNodeWithText("首页").performClick()
        ui.onNodeWithText("映汐播放器").assertIsDisplayed()
    }
}
