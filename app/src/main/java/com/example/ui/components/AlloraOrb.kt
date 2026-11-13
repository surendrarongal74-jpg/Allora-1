package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationConstants
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.GlassMaterial
import com.example.ui.theme.LocalAlloraGlassColors
import com.example.ui.theme.SuccessMint
import com.example.ui.theme.WarningAmber
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

enum class OrbState {
    IDLE,
    LISTENING,
    PROCESSING,
    RESPONDING,
    ERROR,
    OFFLINE
}

@Immutable
internal data class OrbMotionProfile(
    val spinDegreesPerSec: Float,
    val baseScale: Float,
    val breathAmplitude: Float,
    val breathRadPerSec: Float,
    val floatAmplitudeDp: Float,
    val auraMultiplier: Float,
    val ringExpansion: Float
)

internal fun OrbState.toMotionProfile(): OrbMotionProfile = when (this) {
    OrbState.IDLE -> OrbMotionProfile(
        spinDegreesPerSec = 36f,
        baseScale = 1.0f,
        breathAmplitude = 0.018f,
        breathRadPerSec = 2.2f,
        floatAmplitudeDp = 3.2f,
        auraMultiplier = 0.68f,
        ringExpansion = 0f
    )
    OrbState.LISTENING -> OrbMotionProfile(
        spinDegreesPerSec = 84f,
        baseScale = 1.032f,
        breathAmplitude = 0.048f,
        breathRadPerSec = 5.4f,
        floatAmplitudeDp = 4.4f,
        auraMultiplier = 0.96f,
        ringExpansion = 1f
    )
    OrbState.PROCESSING -> OrbMotionProfile(
        spinDegreesPerSec = 195f,
        baseScale = 1.008f,
        breathAmplitude = 0.042f,
        breathRadPerSec = 7.2f,
        floatAmplitudeDp = 2.4f,
        auraMultiplier = 1f,
        ringExpansion = 0.58f
    )
    OrbState.RESPONDING -> OrbMotionProfile(
        spinDegreesPerSec = 104f,
        baseScale = 1.024f,
        breathAmplitude = 0.036f,
        breathRadPerSec = 4.4f,
        floatAmplitudeDp = 3.6f,
        auraMultiplier = 0.90f,
        ringExpansion = 0.85f
    )
    OrbState.ERROR -> OrbMotionProfile(
        spinDegreesPerSec = 16f,
        baseScale = 0.99f,
        breathAmplitude = 0.020f,
        breathRadPerSec = 5.0f,
        floatAmplitudeDp = 1.5f,
        auraMultiplier = 0.85f,
        ringExpansion = 0.25f
    )
    OrbState.OFFLINE -> OrbMotionProfile(
        spinDegreesPerSec = 0f,
        baseScale = 0.96f,
        breathAmplitude = 0f,
        breathRadPerSec = 0f,
        floatAmplitudeDp = 0f,
        auraMultiplier = 0.35f,
        ringExpansion = 0f
    )
}

@Immutable
internal class AlloraOrbSpringDriver(
    val baseScaleAnim: Animatable<Float, AnimationVector1D>,
    val pressScaleAnim: Animatable<Float, AnimationVector1D>,
    val breathAmplitudeAnim: Animatable<Float, AnimationVector1D>,
    val breathSpeedAnim: Animatable<Float, AnimationVector1D>,
    val breathPhaseAnim: Animatable<Float, AnimationVector1D>,
    val floatAmplitudeAnim: Animatable<Float, AnimationVector1D>,
    val orbitalAngleAnim: Animatable<Float, AnimationVector1D>,
    val tiltPhaseAnim: Animatable<Float, AnimationVector1D>,
    val spinVelocityAnim: Animatable<Float, AnimationVector1D>,
    val auraIntensityAnim: Animatable<Float, AnimationVector1D>,
    val ringExpansionAnim: Animatable<Float, AnimationVector1D>
)

@Composable
internal fun rememberAlloraOrbSpringDriver(
    state: OrbState,
    reducedMotion: Boolean,
    isPressed: Boolean
): AlloraOrbSpringDriver {
    val driver = remember {
        val initial = state.toMotionProfile()
        AlloraOrbSpringDriver(
            baseScaleAnim = Animatable(initial.baseScale),
            pressScaleAnim = Animatable(1f),
            breathAmplitudeAnim = Animatable(if (reducedMotion) 0f else initial.breathAmplitude),
            breathSpeedAnim = Animatable(if (reducedMotion) 0f else initial.breathRadPerSec),
            breathPhaseAnim = Animatable(0f),
            floatAmplitudeAnim = Animatable(if (reducedMotion) 0f else initial.floatAmplitudeDp),
            orbitalAngleAnim = Animatable(0f),
            tiltPhaseAnim = Animatable(0f),
            spinVelocityAnim = Animatable(if (reducedMotion) 0f else initial.spinDegreesPerSec),
            auraIntensityAnim = Animatable(initial.auraMultiplier),
            ringExpansionAnim = Animatable(initial.ringExpansion)
        )
    }

    LaunchedEffect(isPressed) {
        driver.pressScaleAnim.animateTo(
            targetValue = if (isPressed) 0.92f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        )
    }

    LaunchedEffect(state, reducedMotion) {
        val profile = state.toMotionProfile()
        val motionScale = if (reducedMotion) 0f else 1f
        coroutineScope {
            launch {
                driver.baseScaleAnim.animateTo(
                    targetValue = profile.baseScale,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
            launch {
                driver.spinVelocityAnim.animateTo(
                    targetValue = profile.spinDegreesPerSec * motionScale,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
            }
            launch {
                driver.breathAmplitudeAnim.animateTo(
                    targetValue = profile.breathAmplitude * motionScale,
                    animationSpec = spring(
                        dampingRatio = 0.72f,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
            launch {
                driver.breathSpeedAnim.animateTo(
                    targetValue = profile.breathRadPerSec * motionScale,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
            }
            launch {
                driver.floatAmplitudeAnim.animateTo(
                    targetValue = profile.floatAmplitudeDp * motionScale,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
            }
            launch {
                driver.auraIntensityAnim.animateTo(
                    targetValue = profile.auraMultiplier,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
            launch {
                driver.ringExpansionAnim.animateTo(
                    targetValue = profile.ringExpansion,
                    animationSpec = spring(
                        dampingRatio = 0.62f,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
        }
    }

    LaunchedEffect(reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        var lastFrameNanos = AnimationConstants.UnspecifiedTime
        val twoPi = (2.0 * PI).toFloat()
        while (isActive) {
            val frameNanos = withInfiniteAnimationFrameNanos { it }
            if (lastFrameNanos != AnimationConstants.UnspecifiedTime) {
                val dtSeconds = ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                val deltaDeg = driver.spinVelocityAnim.value * dtSeconds
                val deltaPhase = driver.breathSpeedAnim.value * dtSeconds
                if (deltaDeg != 0f) {
                    driver.orbitalAngleAnim.snapTo((driver.orbitalAngleAnim.value + deltaDeg) % 360f)
                    driver.tiltPhaseAnim.snapTo((driver.tiltPhaseAnim.value + deltaDeg * 0.58f) % 360f)
                }
                if (deltaPhase != 0f) {
                    driver.breathPhaseAnim.snapTo((driver.breathPhaseAnim.value + deltaPhase) % twoPi)
                }
            }
            lastFrameNanos = frameNanos
        }
    }

    return driver
}

/**
 * Minimal, futuristic translucent quartz glass orb for ALLORA.
 * Preserves spring motion physics across IDLE, LISTENING, PROCESSING, and RESPONDING states
 * with restrained monochrome frosted reflections instead of neon multicolor gradients.
 */
@Composable
fun AlloraAiOrb(
    state: OrbState = OrbState.IDLE,
    size: Dp = 112.dp,
    reducedMotion: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val isDark = glass.isDark
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val driver = rememberAlloraOrbSpringDriver(
        state = state,
        reducedMotion = reducedMotion,
        isPressed = isPressed
    )

    val auraColors = remember(state, isDark) {
        when (state) {
            OrbState.ERROR -> listOf(glass.statusError.copy(alpha = 0.28f), WarningAmber.copy(alpha = 0.12f), Color.Transparent)
            OrbState.OFFLINE -> listOf(Color(0xFF6B7280).copy(alpha = 0.18f), Color.Transparent)
            OrbState.RESPONDING -> listOf(
                if (isDark) Color.White.copy(alpha = 0.22f) else Color(0xFF17191D).copy(alpha = 0.14f),
                SuccessMint.copy(alpha = 0.10f),
                Color.Transparent
            )
            else -> listOf(
                if (isDark) Color.White.copy(alpha = 0.18f) else Color(0xFF17191D).copy(alpha = 0.11f),
                Color.Transparent
            )
        }
    }

    val clickableModifier = if (onClick != null) {
        Modifier.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                val phase = driver.breathPhaseAnim.value
                val floatDp = if (reducedMotion) 0f else sin(phase * 0.72f) * driver.floatAmplitudeAnim.value
                val breathScale = if (reducedMotion) 0f else sin(phase) * driver.breathAmplitudeAnim.value
                translationY = floatDp * density
                val combinedScale = (driver.baseScaleAnim.value + breathScale) * driver.pressScaleAnim.value
                scaleX = combinedScale
                scaleY = combinedScale
                alpha = if (state == OrbState.OFFLINE) 0.55f else 1f
            }
            .then(clickableModifier),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .drawWithCache {
                    val radius = this.size.minDimension / 2f
                    val center = Offset(this.size.width / 2f, this.size.height / 2f)
                    val sphereRadius = radius * 0.72f

                    val auraBrush = Brush.radialGradient(
                        colors = auraColors,
                        center = center,
                        radius = radius
                    )
                    val volumeBrush = Brush.radialGradient(
                        colors = when {
                            state == OrbState.ERROR -> listOf(
                                Color(0xFFF6F7F9).copy(alpha = 0.88f),
                                ErrorCoral.copy(alpha = 0.50f),
                                Color(0xFF28171C).copy(alpha = 0.80f)
                            )
                            isDark -> listOf(
                                Color(0xFFFFFFFF).copy(alpha = 0.44f),
                                Color(0xFFD5D9E0).copy(alpha = 0.24f),
                                Color(0xFF333944).copy(alpha = 0.24f),
                                Color(0xFF0A0C10).copy(alpha = 0.65f)
                            )
                            else -> listOf(
                                Color(0xFFFFFFFF).copy(alpha = 0.96f),
                                Color(0xFFE7EAEF).copy(alpha = 0.82f),
                                Color(0xFFAEB6C2).copy(alpha = 0.46f),
                                Color(0xFF5D6470).copy(alpha = 0.28f)
                            )
                        },
                        center = Offset(center.x - sphereRadius * 0.30f, center.y - sphereRadius * 0.32f),
                        radius = sphereRadius * 1.48f
                    )
                    val primaryCausticBrush = Brush.sweepGradient(
                        colors = if (isDark) {
                            listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.76f),
                                Color(0xFFD5D9E0).copy(alpha = 0.40f),
                                Color.Transparent
                            )
                        } else {
                            listOf(
                                Color.Transparent,
                                Color(0xFF17191D).copy(alpha = 0.62f),
                                Color.White.copy(alpha = 0.85f),
                                Color.Transparent
                            )
                        },
                        center = center
                    )
                    val secondaryCausticBrush = Brush.sweepGradient(
                        colors = if (isDark) {
                            listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.44f),
                                Color.Transparent
                            )
                        } else {
                            listOf(
                                Color.Transparent,
                                Color(0xFF5D6470).copy(alpha = 0.44f),
                                Color.Transparent
                            )
                        },
                        center = center
                    )
                    val rimBrush = Brush.linearGradient(
                        colors = if (isDark) {
                            listOf(
                                Color.White.copy(alpha = 0.82f),
                                Color.White.copy(alpha = 0.22f),
                                Color.White.copy(alpha = 0.48f)
                            )
                        } else {
                            listOf(
                                Color.White,
                                Color(0xFF17191D).copy(alpha = 0.34f),
                                Color.White.copy(alpha = 0.82f)
                            )
                        },
                        start = Offset(center.x - sphereRadius, center.y - sphereRadius),
                        end = Offset(center.x + sphereRadius, center.y + sphereRadius)
                    )
                    val glintCenter = Offset(center.x - sphereRadius * 0.36f, center.y - sphereRadius * 0.36f)
                    val glintBrush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = if (isDark) 0.85f else 0.95f), Color.Transparent),
                        center = glintCenter,
                        radius = sphereRadius * 0.42f
                    )
                    val ribbonTopLeft = Offset(center.x - sphereRadius * 0.82f, center.y - sphereRadius * 0.82f)
                    val ribbonSize = Size(sphereRadius * 1.64f, sphereRadius * 1.64f)
                    val ribbonStroke = Stroke(width = (sphereRadius * 0.08f).coerceAtLeast(1.8f), cap = StrokeCap.Round)

                    val filamentTopLeft = Offset(center.x - sphereRadius * 0.58f, center.y - sphereRadius * 0.58f)
                    val filamentSize = Size(sphereRadius * 1.16f, sphereRadius * 1.16f)
                    val filamentStroke = Stroke(width = (sphereRadius * 0.05f).coerceAtLeast(1.2f), cap = StrokeCap.Round)

                    val innerCoreTopLeft = Offset(center.x - sphereRadius * 0.34f, center.y - sphereRadius * 0.34f)
                    val innerCoreSize = Size(sphereRadius * 0.68f, sphereRadius * 0.68f)
                    val innerCoreStroke = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round)

                    val listeningStroke = Stroke(width = 1.2.dp.toPx())
                    val rimStroke = Stroke(width = 1.2.dp.toPx())
                    val ringColor = when (state) {
                        OrbState.ERROR -> ErrorCoral
                        else -> if (isDark) Color.White else Color(0xFF17191D)
                    }

                    onDrawBehind {
                        val effectiveAngle = if (reducedMotion) 25f else driver.orbitalAngleAnim.value
                        val secondaryPhase = if (reducedMotion) -16f else -driver.tiltPhaseAnim.value
                        val auraAlpha = driver.auraIntensityAnim.value.coerceIn(0f, 1f)
                        val ringProgress = driver.ringExpansionAnim.value.coerceIn(0f, 1.2f)

                        drawCircle(
                            brush = auraBrush,
                            radius = radius,
                            center = center,
                            alpha = auraAlpha
                        )

                        if (ringProgress > 0.02f) {
                            val ringRadius = sphereRadius + (radius * 0.94f - sphereRadius) * ringProgress
                            drawCircle(
                                color = ringColor.copy(alpha = (0.36f * ringProgress).coerceIn(0f, 0.50f)),
                                radius = ringRadius,
                                center = center,
                                style = listeningStroke
                            )
                        }

                        drawCircle(brush = volumeBrush, radius = sphereRadius, center = center)

                        rotate(degrees = effectiveAngle, pivot = center) {
                            drawArc(
                                brush = primaryCausticBrush,
                                startAngle = -15f,
                                sweepAngle = 200f,
                                useCenter = false,
                                topLeft = ribbonTopLeft,
                                size = ribbonSize,
                                style = ribbonStroke
                            )
                        }

                        rotate(degrees = secondaryPhase, pivot = center) {
                            drawArc(
                                brush = secondaryCausticBrush,
                                startAngle = 60f,
                                sweepAngle = 185f,
                                useCenter = false,
                                topLeft = filamentTopLeft,
                                size = filamentSize,
                                style = filamentStroke
                            )
                        }

                        rotate(degrees = effectiveAngle * 1.25f, pivot = center) {
                            drawArc(
                                color = ringColor.copy(alpha = 0.42f),
                                startAngle = 140f,
                                sweepAngle = 110f,
                                useCenter = false,
                                topLeft = innerCoreTopLeft,
                                size = innerCoreSize,
                                style = innerCoreStroke
                            )
                        }

                        drawCircle(
                            brush = rimBrush,
                            radius = sphereRadius,
                            center = center,
                            style = rimStroke
                        )

                        drawCircle(
                            brush = glintBrush,
                            radius = sphereRadius * 0.36f,
                            center = glintCenter
                        )
                    }
                }
        )
    }
}

/**
 * 6. FloatingAssistantOrb — Neutral translucent glass pill housing the AI orb.
 */
@Composable
fun FloatingAssistantOrb(
    promptLabel: String,
    orbState: OrbState = OrbState.IDLE,
    reducedMotion: Boolean = false,
    showLabel: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAlloraGlassColors.current
    val shape = RoundedCornerShape(GlassTokens.PillRadius)

    GlassSurface(
        modifier = modifier.heightIn(min = 54.dp),
        role = GlassSurfaceRole.NAV,
        material = GlassMaterial.FROSTED,
        shape = shape
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 54.dp)
                .clickable(onClick = onClick)
                .testTag("floating_assistant_orb_pill")
                .padding(
                    start = if (showLabel) 6.dp else 5.dp,
                    end = if (showLabel) 14.dp else 5.dp,
                    top = 4.dp,
                    bottom = 4.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AlloraAiOrb(
                state = orbState,
                size = if (showLabel) 44.dp else 46.dp,
                reducedMotion = reducedMotion
            )
            if (showLabel) {
                Spacer(modifier = Modifier.width(6.dp))
                AnimatedContent(targetState = promptLabel, label = "orbPromptText") { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge.copy(
                            color = glass.textPrimary,
                            fontWeight = FontWeight.SemiBold
                        ),
                        maxLines = 1
                    )
                }
            }
        }
    }
}
