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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider

// finesse · register=product · shell=phone-library · palette=mist+berry · material=refractive-glass · motion=fade · density=4
// Native Android surfaces inspired by Apple HIG. No Apple platform assets or APIs.
val Paper = Color(0xFFF5F5F8)
val Ink = Color(0xFF22232A)
val Muted = Color(0xFF62636D)
val Berry = Color(0xFFB91E4B)
val GroupedSurface = Color(0xFFFCFCFE)
val Hairline = Color(0xFF878898).copy(alpha = .15f)

@Composable
fun PlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Berry, onPrimary = Color.White,
            primaryContainer = Color(0xFFF9E6EC), onPrimaryContainer = Color(0xFF721831),
            background = Paper, surface = GroupedSurface, onSurface = Ink, onBackground = Ink,
            secondary = Muted, secondaryContainer = Color(0xFFECECF2), onSecondaryContainer = Ink,
            surfaceVariant = Color(0xFFEEEEF3), onSurfaceVariant = Muted, outline = Hairline,
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
        content = content,
    )
}

internal val LocalGlassBackdrop = staticCompositionLocalOf<GraphicsLayer?> { null }

/** The sampled scene excludes the glass itself, preventing recursive capture. */
@Composable
fun GlassSurface(modifier: Modifier = Modifier, dark: Boolean = false, radius: Int = 26, content: @Composable BoxScope.() -> Unit) {
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
    val rim = remember(dark) { Brush.linearGradient(listOf(Color.White.copy(alpha=if(dark).3f else .96f),Color.White.copy(alpha=if(dark).08f else .24f),Hairline)) }
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
fun ChoicePill(label: String, selected: Boolean, modifier: Modifier = Modifier, dark: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, label = "choice press")
    val fill by animateColorAsState(if (selected) { if (dark) Color(0xFF5C3443) else GroupedSurface } else Color.Transparent, label = "choice selection")
    Box(modifier.graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(RoundedCornerShape(13.dp))
        .background(fill)
        .border(if (focused) 1.5.dp else 0.dp, if (focused) Berry else Color.Transparent, RoundedCornerShape(13.dp))
        .selectable(selected = selected, role = Role.RadioButton, interactionSource = interaction, indication = null, onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Text(label, fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (dark) { if (selected) Color(0xFFFFB4C8) else Color(0xFFCDCDD5) } else if (selected) Berry else Muted,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable
fun PlayerField(label: String, value: String, modifier: Modifier = Modifier, keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
                transformation: VisualTransformation = VisualTransformation.None, trailing: @Composable (() -> Unit)? = null, change: (String) -> Unit) {
    TextField(value = value, onValueChange = change, label = { Text(label, fontSize = 13.sp) }, singleLine = true,
        modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        textStyle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = Ink), keyboardOptions = keyboardOptions,
        visualTransformation = transformation, trailingIcon = trailing,
        colors = TextFieldDefaults.colors(focusedContainerColor = GroupedSurface, unfocusedContainerColor = GroupedSurface,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            focusedLabelColor = Berry, unfocusedLabelColor = Muted, cursorColor = Berry))
}
