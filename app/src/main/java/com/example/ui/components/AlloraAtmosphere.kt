package com.example.ui.components

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.example.ui.theme.DarkAtmosphereBottom
import com.example.ui.theme.DarkAtmosphereMid
import com.example.ui.theme.DarkAtmosphereTop
import com.example.ui.theme.LightAtmosphereBottom
import com.example.ui.theme.LightAtmosphereMid
import com.example.ui.theme.LightAtmosphereTop
import com.example.ui.theme.LocalAlloraGlassColors
import kotlin.math.roundToInt

enum class AtmosphereMood {
    HOME,
    DISCOVER,
    FLIGHTS,
    HOTELS,
    DINING,
    TRIPS,
    APPROVALS,
    ASSISTANT,
    AUTH,
    MAP
}

class GlassOffsetRef(var offset: Offset = Offset.Zero)

@Immutable
data class GlassBackdropState(
    val ambientLayer: GraphicsLayer? = null,
    val ambientOriginRef: GlassOffsetRef = GlassOffsetRef(Offset.Zero),
    val liveContentLayer: GraphicsLayer? = null,
    val liveOriginRef: GlassOffsetRef = GlassOffsetRef(Offset.Zero),
    val activeLayer: GraphicsLayer? = null,
    val activeOriginRef: GlassOffsetRef = GlassOffsetRef(Offset.Zero)
)

val LocalGlassBackdrop = staticCompositionLocalOf { GlassBackdropState() }

/**
 * Neutral, low-contrast architectural canvas for ALLORA's Crystal Light & Smoked Obsidian glassmorphism.
 * Zero photographs, zero wallpaper images, and zero colorful gradients.
 * Renders delicate neutral light planes, mist spheres, and subtle glass caustic arcs into a shared
 * offscreen GraphicsLayer so every GlassSurface blurs and refracts real underlying tonal depth.
 */
@Composable
fun AlloraAtmosphericBackground(
    mood: AtmosphereMood = AtmosphereMood.HOME,
    @Suppress("UNUSED_PARAMETER") @DrawableRes customBackdropRes: Int? = null,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val glass = LocalAlloraGlassColors.current
    val isDark = glass.isDark
    val ambientLayer = rememberGraphicsLayer()
    val ambientOriginRef = remember { GlassOffsetRef(Offset.Zero) }
    val recordCache = remember {
        object {
            var width: Float = -1f
            var height: Float = -1f
            var isDark: Boolean = isDark
            var mood: AtmosphereMood = mood
        }
    }

    val baseBrush = remember(isDark) {
        if (isDark) {
            Brush.verticalGradient(
                colors = listOf(
                    DarkAtmosphereTop,
                    DarkAtmosphereMid,
                    DarkAtmosphereBottom
                )
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    LightAtmosphereTop,
                    LightAtmosphereMid,
                    LightAtmosphereBottom
                )
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(glass.canvasBase)
    ) {
        // Layer 1: Passive neutral architectural field recorded into ambientLayer
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    if (coords.isAttached) {
                        ambientOriginRef.offset = coords.positionInRoot()
                    }
                }
                .drawWithContent {
                    if (size.width >= 1f && size.height >= 1f) {
                        if (recordCache.width != size.width ||
                            recordCache.height != size.height ||
                            recordCache.isDark != isDark ||
                            recordCache.mood != mood
                        ) {
                            recordCache.width = size.width
                            recordCache.height = size.height
                            recordCache.isDark = isDark
                            recordCache.mood = mood
                            ambientLayer.record {
                                this@drawWithContent.drawContent()
                            }
                        }
                        drawLayer(ambientLayer)
                    } else {
                        drawContent()
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(baseBrush)
                    .drawWithCache {
                        val w = size.width
                        val h = size.height
                        val maxDim = maxOf(w, h).coerceAtLeast(1f)

                        val topLightCenter = Offset(w * 0.24f, h * 0.16f)
                        val midRightCenter = Offset(w * 0.86f, h * 0.44f)
                        val lowerMistCenter = Offset(w * 0.20f, h * 0.78f)
                        val bottomGlowCenter = Offset(w * 0.78f, h * 0.90f)

                        val topBeam = Brush.radialGradient(
                            colors = if (isDark) {
                                listOf(Color(0x2BFFFFFF), Color(0x12D5DCE8), Color.Transparent)
                            } else {
                                listOf(Color(0xF5FFFFFF), Color(0x70FFFFFF), Color.Transparent)
                            },
                            center = topLightCenter,
                            radius = (w * 0.85f).coerceAtLeast(1f)
                        )
                        val midSlatePlane = Brush.radialGradient(
                            colors = if (isDark) {
                                listOf(Color(0x24384152), Color(0x10242A35), Color.Transparent)
                            } else {
                                listOf(Color(0x88B4BECB), Color(0x3DC7CFD9), Color.Transparent)
                            },
                            center = midRightCenter,
                            radius = (w * 0.78f).coerceAtLeast(1f)
                        )
                        val lowerDepth = Brush.radialGradient(
                            colors = if (isDark) {
                                listOf(Color(0x20FFFFFF), Color(0x0B2B313D), Color.Transparent)
                            } else {
                                listOf(Color(0x75AEB8C6), Color(0x30C5CDD8), Color.Transparent)
                            },
                            center = lowerMistCenter,
                            radius = (w * 0.72f).coerceAtLeast(1f)
                        )
                        val bottomHighlight = Brush.radialGradient(
                            colors = if (isDark) {
                                listOf(Color(0x18FFFFFF), Color.Transparent)
                            } else {
                                listOf(Color(0xCCFFFFFF), Color.Transparent)
                            },
                            center = bottomGlowCenter,
                            radius = (w * 0.65f).coerceAtLeast(1f)
                        )
                        val diagonalRibbon = Brush.linearGradient(
                            colors = if (isDark) {
                                listOf(Color.Transparent, Color(0x14FFFFFF), Color.Transparent)
                            } else {
                                listOf(Color.Transparent, Color(0x99FFFFFF), Color(0x269AA5B4), Color.Transparent)
                            },
                            start = Offset(0f, h * 0.12f),
                            end = Offset(w, h * 0.82f)
                        )

                        val arcColorLight = if (isDark) Color(0x17FFFFFF) else Color(0x52FFFFFF)
                        val arcColorSlate = if (isDark) Color(0x12FFFFFF) else Color(0x26758090)
                        val arcStrokeWide = Stroke(width = (maxDim * 0.032f).coerceIn(18f, 44f), cap = StrokeCap.Round)
                        val arcStrokeFine = Stroke(width = (maxDim * 0.008f).coerceIn(4f, 12f), cap = StrokeCap.Round)

                        onDrawBehind {
                            if (mood != AtmosphereMood.MAP) {
                                drawRect(brush = diagonalRibbon)
                                drawCircle(brush = topBeam, center = topLightCenter, radius = (w * 0.85f).coerceAtLeast(1f))
                                drawCircle(brush = midSlatePlane, center = midRightCenter, radius = (w * 0.78f).coerceAtLeast(1f))
                                drawCircle(brush = lowerDepth, center = lowerMistCenter, radius = (w * 0.72f).coerceAtLeast(1f))
                                drawCircle(brush = bottomHighlight, center = bottomGlowCenter, radius = (w * 0.65f).coerceAtLeast(1f))

                                drawArc(
                                    color = arcColorLight,
                                    startAngle = 205f,
                                    sweepAngle = 125f,
                                    useCenter = false,
                                    topLeft = Offset(-w * 0.25f, h * 0.08f),
                                    size = Size(w * 1.15f, w * 1.15f),
                                    style = arcStrokeWide
                                )
                                drawArc(
                                    color = arcColorSlate,
                                    startAngle = 15f,
                                    sweepAngle = 135f,
                                    useCenter = false,
                                    topLeft = Offset(w * 0.18f, h * 0.42f),
                                    size = Size(w * 1.05f, w * 1.05f),
                                    style = arcStrokeFine
                                )
                            }
                        }
                    }
            )
        }

        val backdropState = remember(ambientLayer, ambientOriginRef) {
            GlassBackdropState(
                ambientLayer = ambientLayer,
                ambientOriginRef = ambientOriginRef,
                liveContentLayer = null,
                liveOriginRef = GlassOffsetRef(Offset.Zero),
                activeLayer = ambientLayer,
                activeOriginRef = ambientOriginRef
            )
        }
        CompositionLocalProvider(LocalGlassBackdrop provides backdropState) {
            content()
        }
    }
}

/**
 * Captures the main scrollable screen subtree into an unblurred offscreen GraphicsLayer so
 * sibling overlay chrome (FloatingNavigation, FloatingAssistantOrb) can render real blurred
 * backdrop slices of scrolling content underneath them without recursive self-blur.
 */
@Composable
fun GlassLiveBackdropScope(
    modifier: Modifier = Modifier,
    overlayContent: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit
) {
    val parentBackdrop = LocalGlassBackdrop.current
    val liveContentLayer = rememberGraphicsLayer()
    val liveOriginRef = remember { GlassOffsetRef(Offset.Zero) }

    val contentBackdrop = remember(parentBackdrop, liveContentLayer, liveOriginRef) {
        parentBackdrop.copy(
            liveContentLayer = liveContentLayer,
            liveOriginRef = liveOriginRef,
            activeLayer = parentBackdrop.ambientLayer,
            activeOriginRef = parentBackdrop.ambientOriginRef
        )
    }
    val overlayBackdrop = remember(parentBackdrop, liveContentLayer, liveOriginRef) {
        parentBackdrop.copy(
            liveContentLayer = liveContentLayer,
            liveOriginRef = liveOriginRef,
            activeLayer = liveContentLayer,
            activeOriginRef = liveOriginRef
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    if (coords.isAttached) {
                        liveOriginRef.offset = coords.positionInRoot()
                    }
                }
                .drawWithContent {
                    if (size.width >= 1f && size.height >= 1f) {
                        liveContentLayer.record {
                            this@drawWithContent.drawContent()
                        }
                        drawLayer(liveContentLayer)
                    } else {
                        drawContent()
                    }
                }
        ) {
            CompositionLocalProvider(LocalGlassBackdrop provides contentBackdrop) {
                content()
            }
        }

        CompositionLocalProvider(LocalGlassBackdrop provides overlayBackdrop) {
            overlayContent()
        }
    }
}

internal fun createPlatformBlurEffect(radiusPx: Float): BlurEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val clamped = radiusPx.coerceIn(1f, 36f)
    return if (clamped.roundToInt() > 0) {
        BlurEffect(clamped, clamped, TileMode.Clamp)
    } else {
        null
    }
}
