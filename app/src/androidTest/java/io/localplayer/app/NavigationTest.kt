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
        ui.onNodeWithText("映汐").assertIsDisplayed()
        ui.onNodeWithText("避难所").performClick()
        ui.onNodeWithContentDescription("添加避难所").assertIsDisplayed()
        ui.onAllNodesWithText("拾荒记录").filter(hasClickAction()).onFirst().performClick()
        ui.onNodeWithContentDescription("清空拾荒记录").assertIsDisplayed()
        ui.onNodeWithText("放映站").performClick()
        ui.onNodeWithText("映汐").assertIsDisplayed()
    }
}
