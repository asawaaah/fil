package com.asawaaah.fil.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.backdrop.shadow.Shadow

object Accessibility {
    /** Android's "high contrast text" option (Settings › Accessibility). */
    fun highContrastText(context: android.content.Context): Boolean = runCatching {
        android.provider.Settings.Secure.getInt(context.contentResolver, "high_text_contrast_enabled", 0) == 1
    }.getOrDefault(false)
}

/** False when the user (or the system's high-contrast text) asks for opaque bars. */
val LocalGlassEnabled = staticCompositionLocalOf { true }

/** Height of the floating tab bar, so that screens can scroll their content above it. */
val LocalBottomOverlay = compositionLocalOf { 0.dp }

val CapsuleShape = RoundedCornerShape(percent = 50)

/**
 * "Liquid glass" surface: the content behind is refracted at the edges, blurred and made more
 * vivid, then covered with a tint strong enough to keep text and icons readable.
 * Opaque surface instead when glass is disabled.
 */
@Composable
fun Modifier.glassSurface(
    backdrop: Backdrop,
    shape: CornerBasedShape = CapsuleShape,
    tint: Color? = null,
): Modifier {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    if (!LocalGlassEnabled.current) {
        return this
            .shadow(4.dp, shape)
            .background(tint ?: scheme.surfaceContainerHigh, shape)
            .border(1.dp, scheme.outlineVariant, shape)
    }
    // Tints chosen so that light text on dark glass (and dark text on light glass) keeps at
    // least a 4.5:1 contrast even over a white or black picture.
    // Without blur (Android 11 and older), a nearly opaque tint keeps the text readable.
    val alpha = when {
        !isRenderEffectSupported() -> 0.92f
        dark -> 0.66f
        else -> 0.62f
    }
    val surface = (tint ?: scheme.surface).copy(alpha = alpha)
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(10.dp.toPx())
            lens(14.dp.toPx(), 28.dp.toPx())
        },
        highlight = { Highlight.Default.copy(alpha = if (dark) 0.55f else 0.9f) },
        shadow = { Shadow(radius = 18.dp, color = Color.Black.copy(alpha = if (dark) 0.35f else 0.12f)) },
        onDrawSurface = { drawRect(surface) },
    )
}

/** Round glass button, 52 dp (above the 48 dp minimum touch target). */
@Composable
fun GlassCircleButton(
    backdrop: Backdrop,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(52.dp)
            .glassSurface(backdrop, CircleShape)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = MaterialTheme.colorScheme.onSurface)
    }
}

/** Space between the status bar and the floating top bar. */
private val TopBarGap = 8.dp

/** Floating top bar: back button, then the title and the actions in one glass capsule. */
@Composable
fun GlassTopBar(
    backdrop: Backdrop,
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    titleContent: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = TopBarGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack != null) GlassCircleButton(backdrop, Icons.AutoMirrored.Filled.ArrowBack, "Retour", onBack)
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 52.dp)
                .glassSurface(backdrop)
                .padding(start = 20.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 6.dp)) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    if (titleContent != null) {
                        titleContent()
                    } else {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                }
            }
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                actions()
            }
        }
    }
}

data class GlassTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector = icon)

/** Floating tab bar in a glass capsule. Labels always visible; selection marked by a tinted pill. */
@Composable
fun GlassTabBar(
    backdrop: Backdrop,
    tabs: List<GlassTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    // Labels follow the system font size up to 150 %; beyond that four tabs no longer fit
    // side by side (the screens' own text keeps growing).
    val fontScale = LocalDensity.current.fontScale
    val labelStyle = MaterialTheme.typography.labelMedium.let {
        if (fontScale > MaxTabFontScale) it.copy(fontSize = it.fontSize * (MaxTabFontScale / fontScale)) else it
    }
    Row(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .heightIn(min = 64.dp)
            .glassSurface(backdrop)
            .padding(6.dp)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = index == selectedIndex
            val color = if (selected) scheme.onPrimaryContainer else scheme.onSurface
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .clip(CapsuleShape)
                    .background(if (selected) scheme.primaryContainer.copy(alpha = 0.9f) else Color.Transparent)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(if (selected) tab.selectedIcon else tab.icon, null, tint = color)
                Text(tab.label, style = labelStyle, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private const val MaxTabFontScale = 1.5f

/** Height reserved at the bottom of the screens for the floating tab bar (system bar excluded). */
val TabBarReservedHeight: Dp = 96.dp

/** Floating action in a tinted glass capsule. */
@Composable
fun GlassFab(
    backdrop: Backdrop,
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .heightIn(min = 56.dp)
            .glassSurface(backdrop, tint = scheme.primaryContainer)
            .clip(CapsuleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = scheme.onPrimaryContainer)
        Text(text, style = MaterialTheme.typography.labelLarge, color = scheme.onPrimaryContainer)
    }
}

/**
 * Screen with floating glass bars: the content (usually a list) scrolls under the top bar and
 * the tab bar, which refract and blur it. [content] receives the padding to apply so that
 * nothing stays hidden under the bars.
 */
@Composable
fun GlassScreen(
    title: String,
    onBack: (() -> Unit)? = null,
    titleContent: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    snackbar: SnackbarHostState? = null,
    belowTopBar: (@Composable () -> Unit)? = null,
    floating: (@Composable BoxScope.(backdrop: Backdrop, bottom: Dp) -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val backdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    var topHeight by remember { mutableStateOf(0.dp) }
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottom = maxOf(LocalBottomOverlay.current, navInset)
    val background = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize().background(background)) {
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(background),
        ) {
            // Default text colour for the screen (what Scaffold used to provide): without it,
            // plain texts would be black, unreadable in dark mode.
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                content(PaddingValues(top = topHeight, bottom = bottom))
            }
        }
        // Keeps the status bar (clock, battery) readable over the scrolling content: solid down
        // to the top of the floating bar, then a fade hidden behind the bar itself.
        val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val solid = statusInset + TopBarGap
        val fade = 18.dp
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(solid + fade)
                .background(
                    Brush.verticalGradient(
                        0f to background,
                        solid / (solid + fade) to background,
                        1f to Color.Transparent,
                    ),
                ),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(navInset + 12.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, background.copy(alpha = 0.85f), background))),
        )
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .onSizeChanged { topHeight = with(density) { it.height.toDp() } },
        ) {
            GlassTopBar(backdrop, title, onBack, titleContent = titleContent, actions = actions)
            belowTopBar?.invoke()
        }
        floating?.invoke(this, backdrop, bottom)
        if (snackbar != null) {
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + 8.dp))
        }
    }
}
