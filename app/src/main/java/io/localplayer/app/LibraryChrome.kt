package io.localplayer.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
internal fun LibraryChrome(modifier:Modifier,showTools:Boolean,activeTool:String?,onTool:(String)->Unit,
                           header:@Composable ()->Unit,content:@Composable ColumnScope.()->Unit){
    val scene=rememberGraphicsLayer()
    var toolsTop by remember{mutableIntStateOf(0)}
    Box(modifier.fillMaxSize().background(Paper)){
        Column(Modifier.fillMaxSize().drawWithContent{
            if(showTools){scene.record{this@drawWithContent.drawContent()};drawLayer(scene)}else drawContent()
        }.background(Paper).windowInsetsPadding(WindowInsets.safeDrawing)){
            Box(Modifier.onGloballyPositioned{toolsTop=it.boundsInRoot().bottom.roundToInt()}){header()}
            if(showTools)Spacer(Modifier.height(64.dp))
            content()
        }
        if(showTools&&toolsTop>0)CompositionLocalProvider(LocalGlassBackdrop provides scene){
            LibraryToolNavigation(activeTool,onTool,Modifier.offset{IntOffset(0,toolsTop)}
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(horizontal=16.dp,vertical=6.dp))
        }
    }
}

@Composable
private fun LibraryToolNavigation(active:String?,onTool:(String)->Unit,modifier:Modifier){
    val config=LocalConfiguration.current
    val zh=config.locales[0].language=="zh"
    val tools=listOf("index","backup","repair")
    val labels=if(zh)listOf("收录","行囊","检修")else listOf("Index","Backup","Repair")
    val descriptions=if(zh)listOf("收录目录","行囊备份","检修台")else listOf("Index folders","Travel backup","Repair bench")
    val icons=listOf(Icons.Rounded.FolderOpen,Icons.Rounded.Backpack,Icons.Rounded.Build)
    GlassSurface(modifier.fillMaxWidth(),radius=28){
        Row(Modifier.fillMaxWidth().padding(4.dp).selectableGroup(),horizontalArrangement=Arrangement.spacedBy(4.dp)){
            tools.forEachIndexed{index,key->
                val interaction=remember{MutableInteractionSource()}
                val pressed by interaction.collectIsPressedAsState()
                val focused by interaction.collectIsFocusedAsState()
                val selected=key==active
                val scale by animateFloatAsState(if(pressed).97f else 1f,label="catalog-tool-press")
                val tint by animateColorAsState(if(selected||pressed)Berry.copy(alpha=.12f)else Color.Transparent,label="catalog-tool-tint")
                Row(Modifier.weight(1f).graphicsLayer{scaleX=scale;scaleY=scale}.clip(RoundedCornerShape(22.dp))
                    .background(tint).border(.8.dp,if(focused)Berry else if(selected)Berry.copy(alpha=.25f)else Color.Transparent,RoundedCornerShape(22.dp))
                    .selectable(selected,interactionSource=interaction,indication=null,role=Role.Tab,onClick={onTool(key)})
                    .heightIn(min=48.dp).padding(horizontal=if(config.fontScale>=1.5f)4.dp else 8.dp).testTag("catalog-$key").semantics{contentDescription=descriptions[index]},
                    horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){
                    if(config.fontScale<1.5f){Icon(icons[index],null,Modifier.size(17.dp),tint=if(selected)Berry else Ink);Spacer(Modifier.width(5.dp))}
                    Text(labels[index],color=if(selected)Berry else Ink,fontSize=13.sp,fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
            }
        }
    }
}
