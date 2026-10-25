package io.localplayer.app

import androidx.compose.foundation.layout.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ConnectionProtocolSelector(external:Boolean,selected:String,select:(String)->Unit){
    var expanded by remember{mutableStateOf(false)}
    val choices=if(external)CloudProvider.entries.map{it.title}+"WebDAV"else listOf("SMB","SFTP")
    fun icon(choice:String)=when(choice){"SMB"->Icons.Rounded.Dns;"SFTP"->Icons.Rounded.Terminal;"WebDAV"->Icons.Rounded.Link;else->Icons.Rounded.Cloud}
    ExposedDropdownMenuBox(expanded,{expanded=it}){
        TextField(value=selected,onValueChange={},readOnly=true,singleLine=true,label={Text(if(external)"接入方式"else"连接协议")},
            leadingIcon={Icon(icon(selected),null,tint=Berry)},
            trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded)},shape=RoundedCornerShape(18.dp),
            colors=TextFieldDefaults.colors(focusedContainerColor=GroupedSurface,unfocusedContainerColor=GroupedSurface,
                focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent),
            modifier=Modifier.fillMaxWidth().menuAnchor().testTag("connection-protocol-selector"))
        ExposedDropdownMenu(expanded,{expanded=false},shape=RoundedCornerShape(22.dp),containerColor=GroupedSurface){
            choices.forEach{choice->DropdownMenuItem(text={Text(choice,fontSize=16.sp)},
                leadingIcon={Icon(icon(choice),null,tint=if(choice==selected)Berry else Muted)},
                trailingIcon={if(choice==selected)Icon(Icons.Rounded.Check,null,tint=Berry)},
                modifier=Modifier.heightIn(min=52.dp).testTag("connection-choice:$choice").semantics{this.selected=choice==selected},
                onClick={expanded=false;select(choice)})}
        }
    }
}

@Composable internal fun ConnectionCategoryTabs(external:Boolean,select:(Boolean)->Unit){
 androidx.compose.foundation.layout.Row(
  Modifier.fillMaxWidth().selectableGroup(),
  horizontalArrangement=androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
 ){
  listOf(false to "幸存者据点",true to "信号塔").forEach{(tower,title)->
   val active=tower==external
   Surface(onClick={select(tower)},color=if(active)Berry.copy(alpha=.09f)else GroupedSurface,
    shape=RoundedCornerShape(16.dp),modifier=Modifier.weight(1f).testTag(if(tower)"source-category-tower"else"source-category-outpost")
     .semantics{selected=active;role=Role.Tab}){
    androidx.compose.foundation.layout.Box(Modifier.heightIn(min=52.dp).padding(horizontal=8.dp,vertical=12.dp),contentAlignment=androidx.compose.ui.Alignment.Center){
     Text(title,color=if(active)Berry else Muted,fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
    }
   }
  }
 }
}
