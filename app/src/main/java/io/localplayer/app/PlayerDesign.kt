package io.localplayer.app

import android.graphics.RuntimeShader
import android.view.WindowManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider

// finesse · register=product · shell=phone-library · palette=warm-paper+berry · material=refractive-glass · motion=fade · density=4
// Native Android surfaces inspired by Apple HIG. No Apple platform assets or APIs.
val Paper:Color @Composable get()=MaterialTheme.colorScheme.background
val Ink:Color @Composable get()=MaterialTheme.colorScheme.onSurface
val Muted:Color @Composable get()=MaterialTheme.colorScheme.onSurfaceVariant
val Berry:Color @Composable get()=MaterialTheme.colorScheme.primary
val GroupedSurface:Color @Composable get()=MaterialTheme.colorScheme.surface
val Hairline:Color @Composable get()=MaterialTheme.colorScheme.outline

@Composable
fun PlayerTheme(content: @Composable () -> Unit) {
    val dark=libraryDarkMode()
    val view=LocalView.current
    SideEffect {
        val activity=generateSequence(view.context){(it as? android.content.ContextWrapper)?.baseContext}.filterIsInstance<android.app.Activity>().firstOrNull()
        activity?.window?.let{window->
            androidx.core.view.WindowCompat.getInsetsController(window,view).apply{isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark}
        }
    }
    MaterialTheme(
        colorScheme = if(dark)darkColorScheme(
            primary=Color(0xFFFFA1BC),onPrimary=Color(0xFF53102A),primaryContainer=Color(0xFF67273B),onPrimaryContainer=Color(0xFFFFD9E3),
            background=Color(0xFF191C1A),surface=Color(0xFF232824),onSurface=Color(0xFFE8E8DF),onBackground=Color(0xFFE8E8DF),
            secondary=Color(0xFFBBC0B4),secondaryContainer=Color(0xFF383F37),onSecondaryContainer=Color(0xFFE3E7DA),
            surfaceVariant=Color(0xFF30372F),onSurfaceVariant=Color(0xFFBBC0B4),outline=Color(0xFF8D9788).copy(alpha=.30f)
        ) else lightColorScheme(
            primary=Color(0xFFB91E4B),onPrimary=Color.White,primaryContainer=Color(0xFFF9E6EC),onPrimaryContainer=Color(0xFF721831),
            background=Color(0xFFF3EFE6),surface=Color(0xFFFCF9F2),onSurface=Color(0xFF2B302D),onBackground=Color(0xFF2B302D),
            secondary=Color(0xFF65685F),secondaryContainer=Color(0xFFE6E4D9),onSecondaryContainer=Color(0xFF2B302D),
            surfaceVariant=Color(0xFFEDE9DF),onSurfaceVariant=Color(0xFF65685F),outline=Color(0xFF868B7B).copy(alpha=.15f)
        ),
        typography = Typography(
            headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.7).sp),
            titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        ),
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(26.dp), extraLarge = RoundedCornerShape(32.dp)),
        content = { CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { content() } },
    )
}

internal val LocalGlassBackdrop = staticCompositionLocalOf<GraphicsLayer?> { null }

/** The sampled scene excludes the glass itself, preventing recursive capture. */
@Composable
fun GlassSurface(modifier: Modifier = Modifier, dark: Boolean = MaterialTheme.colorScheme.background.luminance()<.2f, radius: Int = 26, content: @Composable BoxScope.() -> Unit) {
    val scene = LocalGlassBackdrop.current
    val blurred = rememberGraphicsLayer()
    val lens = remember { RuntimeShader("""
        uniform shader content;
        uniform float2 resolution;
        half4 main(float2 p) {
            float2 v = (p / resolution - 0.5) * 2.0;
            float edge = pow(clamp(max(abs(v.x), abs(v.y)), 0.0, 1.0), 6.0);
            float2 refracted = clamp(p + v * edge * 4.0, float2(0.5), resolution - 0.5);
            return content.eval(refracted);
        }
    """.trimIndent()) }
    val blurPx = with(LocalDensity.current) { 18.dp.toPx() }
    val blurEffect = remember(blurPx) {
        android.graphics.RenderEffect.createBlurEffect(blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP)
    }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val wash = remember(dark) { Brush.linearGradient(if(dark)listOf(Color.White.copy(alpha=.07f),Color.Transparent)else listOf(Color.White.copy(alpha=.46f),Color.White.copy(alpha=.04f))) }
    val hairline=Hairline
    val rim = remember(dark,hairline) { Brush.linearGradient(listOf(Color.White.copy(alpha=if(dark).3f else .96f),Color.White.copy(alpha=if(dark).08f else .24f),hairline)) }
    val shape = RoundedCornerShape(radius.dp)
    Box(modifier
        .onGloballyPositioned { offset = it.positionInRoot() }
        .clip(shape)
        .drawWithCache {
            // RenderEffect snapshots uniforms: set the current size before creating it.
            lens.setFloatUniform("resolution", size.width.coerceAtLeast(1f), size.height.coerceAtLeast(1f))
            val effect = android.graphics.RenderEffect.createChainEffect(
                android.graphics.RenderEffect.createRuntimeShaderEffect(lens, "content"), blurEffect
            ).asComposeRenderEffect()
            onDrawWithContent {
            if (scene != null) {
                blurred.renderEffect = effect
                blurred.record {
                    translate(-offset.x, -offset.y) { drawLayer(scene) }
                }
                drawLayer(blurred)
            }
            // A legible material remains when blur is unavailable or the scene is uniform.
            drawRect(if (dark) Color(0xFF202128).copy(alpha = if(scene!=null).76f else .94f) else Color(0xFFFCFCFF).copy(alpha = if(scene!=null).66f else .94f))
            drawRect(wash)
            drawContent()
            }
        }
        .border(.7.dp, rim, shape),
        content = content)
}

/** Use the OS compositor for real blur behind a sheet, with a solid material fallback. */
@Composable
fun SheetGlassEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val previousDim = window?.attributes?.dimAmount
        val previousBlur = window?.attributes?.blurBehindRadius
        val hadBlur = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_BLUR_BEHIND) != 0
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = 28 }
        }
        onDispose {
            if (window != null) {
                if (!hadBlur) window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply {
                    if (previousBlur != null) blurBehindRadius = previousBlur
                    if (previousDim != null) dimAmount = previousDim
                }
            }
        }
    }
}

@Composable
fun ChoicePill(label: String, selected: Boolean, modifier: Modifier = Modifier, dark: Boolean = MaterialTheme.colorScheme.background.luminance()<.2f, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, label = "choice press")
    val fill by animateColorAsState(if (selected) { if (dark) Color(0xFF5C3443) else Berry.copy(alpha=.10f) } else Color.Transparent, label = "choice selection")
    Box(modifier.graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(RoundedCornerShape(13.dp))
        .background(fill)
        .border(1.dp, if (focused || selected) (if(dark)Color(0xFFFFB4C8)else Berry).copy(alpha=if(focused).8f else .3f) else Color.Transparent, RoundedCornerShape(13.dp))
        .selectable(selected = selected, role = Role.RadioButton, interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Text(label, fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (dark) { if (selected) Color(0xFFFFB4C8) else Color(0xFFCDCDD5) } else if (selected) Berry else Muted,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** The complete row is a single selection target; the check never steals label space. */
@Composable internal fun SelectionRow(label: String, selected: Boolean, modifier: Modifier = Modifier, choose: () -> Unit) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
        .background(if(selected)Berry.copy(alpha=.09f)else GroupedSurface)
        .border(1.dp,if(selected)Berry.copy(alpha=.28f)else Hairline,RoundedCornerShape(15.dp))
        .selectable(selected=selected,role=Role.RadioButton,onClick=choose).heightIn(min=54.dp).padding(horizontal=14.dp,vertical=13.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(label,Modifier.weight(1f),fontSize=15.sp,lineHeight=22.sp,fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Normal,
            color=if(selected)Berry else Ink,maxLines=3,overflow=TextOverflow.Ellipsis)
        Icon(if(selected)Icons.Rounded.Check else Icons.Rounded.RadioButtonUnchecked,null,Modifier.size(21.dp),tint=if(selected)Berry else Muted.copy(alpha=.5f))
    }
}

@Composable internal fun SourceFilter(label: String, selected: Boolean, click: () -> Unit) {
    FilterChip(selected=selected,onClick=click,shape=RoundedCornerShape(14.dp),
        label={Text(label,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.widthIn(max=168.dp),fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Normal)},
        leadingIcon={Icon(Icons.Rounded.Check,null,Modifier.size(16.dp),tint=if(selected)Berry else Color.Transparent)},
        colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Berry.copy(alpha=.10f),selectedLabelColor=Berry),
        border=FilterChipDefaults.filterChipBorder(enabled=true,selected=selected,selectedBorderColor=Berry.copy(alpha=.25f),borderColor=Hairline))
}

@Composable
fun PlayerField(label: String, value: String, modifier: Modifier = Modifier, keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
                transformation: VisualTransformation = VisualTransformation.None, trailing: @Composable (() -> Unit)? = null, change: (String) -> Unit) {
    TextField(value = value, onValueChange = change, label = { Text(label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }, singleLine = true,
        modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        textStyle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = Ink), keyboardOptions = keyboardOptions,
        visualTransformation = transformation, trailingIcon = trailing,
        colors = TextFieldDefaults.colors(focusedContainerColor = GroupedSurface, unfocusedContainerColor = GroupedSurface,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            focusedLabelColor = Berry, unfocusedLabelColor = Muted, cursorColor = Berry))
}
