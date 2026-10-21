package io.localplayer.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ConnectionProtocolSelector(external:Boolean,selected:String,select:(String)->Unit){
    var expanded by remember{mutableStateOf(false)}
    val choices=if(external)listOf("阿里云盘","WebDAV")else listOf("SMB","SFTP")
    ExposedDropdownMenuBox(expanded,{expanded=it}){
        OutlinedTextField(value=selected,onValueChange={},readOnly=true,singleLine=true,label={Text(if(external)"接入方式"else"连接协议")},
            trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded)},shape=RoundedCornerShape(17.dp),
            modifier=Modifier.fillMaxWidth().menuAnchor().testTag("connection-protocol-selector"))
        ExposedDropdownMenu(expanded,{expanded=false},containerColor=GroupedSurface){
            choices.forEach{choice->DropdownMenuItem(text={Text(choice)},onClick={expanded=false;select(choice)})}
        }
    }
}
