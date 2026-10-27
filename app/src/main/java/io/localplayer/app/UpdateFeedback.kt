package io.localplayer.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable internal fun UpdateFeedback(vm:SettingsViewModel,modifier:Modifier=Modifier){
    var text by remember{mutableStateOf("")}
    var visible by remember{mutableStateOf(false)}
    val accessibility=LocalAccessibilityManager.current
    LaunchedEffect(vm.feedbackRevision){
        if(vm.feedbackRevision==0)return@LaunchedEffect
        text=vm.message.orEmpty();visible=true
        delay(accessibility?.calculateRecommendedTimeoutMillis(6000L,containsIcons=false,containsText=true,containsControls=false)?:6000L)
        visible=false
    }
    AnimatedVisibility(visible,modifier.padding(horizontal=20.dp).widthIn(max=360.dp),enter=fadeIn(),exit=fadeOut()){
        GlassSurface(radius=25){
            Text(text,Modifier.padding(horizontal=18.dp,vertical=14.dp).testTag("update-toast").semantics{liveRegion=LiveRegionMode.Polite},fontSize=14.sp,lineHeight=20.sp,color=Ink)
        }
    }
}
