package com.music.bitchord.ui.components

import com.music.bitchord.R
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import com.music.bitchord.ui.theme.AccentRed
import com.music.bitchord.ui.icons.BitChordIcons
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import dev.chrisbanes.haze.HazeStyle
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.thumbnailBorder
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * The transport buttons' touch target. Material's default 48dp is what a bar
 * this slim is really made of, so it sets the height on its own.
 */
private val GLYPH_SLOT = 40.dp

/**
 * The play and skip glyphs themselves.
 *
 * Deliberately grown inside [GLYPH_SLOT] rather than by growing the slot: the
 * slot is level with the 40dp artwork opposite it, and it is the taller of the
 * two that sets the row's height — so a bigger slot would make the whole bar
 * taller, which is not what a bigger glyph is being asked for. At 32 there is
 * still 4dp of clearance to the slot's edge on every side.
 */
private val GLYPH_SIZE = 32.dp

/** The spinner that stands in for the play glyph, kept in proportion to it. */
private val SPINNER_SIZE = 22.dp

/**
 * The gap between the two transport controls.
 *
 * Material asks for at least 8dp between adjacent touch targets, and these had
 * none: two [GLYPH_SLOT] boxes sharing an edge, so the boundary between "pause"
 * and "skip" was a line with nothing either side of it. What space there looked
 * to be was only the margin each glyph keeps inside its own slot, and a thumb
 * lands on a target's edge far more often than it lands on a glyph's.
 *
 * Taken from the title's width rather than the bar's height, so nothing above
 * or below it moves.
 */
private val TRANSPORT_GAP = 8.dp

/**
 * Vertical padding, which with the 40dp artwork sets the bar's height at 56dp
 * and so its pill radius at 28.
 */
private val ROW_PADDING_VERTICAL = 8.dp

/**
 * Horizontal padding, deliberately larger than the vertical.
 *
 * A pill's ends are semicircles, so the edge nearest the artwork is not the
 * one beside it but the one curving away above and below it. At the artwork's
 * top corner that edge has already come 8.4dp in from the left — level with
 * where square corners would have put the whole side. Padding the ends by the
 * vertical figure would leave the artwork touching the curve; 12 clears it
 * with room, and reads as centred rather than jammed into the round.
 */
private val ROW_PADDING_HORIZONTAL = 12.dp

/**
 * The artwork's corner, on the 8dp every other thumbnail in the app carries.
 *
 * It used to be 7, picked so the bar's corner could sit concentric with it.
 * A pill has no corner to be concentric with — its radius is whatever half the
 * height happens to be — so that constraint is gone and the artwork can go
 * back to matching [SongRow].
 */
private val ART_CORNER = 8.dp

/** Distance that makes a horizontal drag an intentional track change. */
private val TRACK_SWIPE_THRESHOLD = 72.dp

/**
 * Shared gesture for both mini-player materials. A left swipe advances through
 * the queue; a right swipe goes back, matching the full player's artwork
 * gesture. Waiting until drag end prevents one long gesture from skipping more
 * than one item.
 */
@Composable
internal fun Modifier.miniPlayerTrackSwipe(
    onNext: () -> Unit,
    onPrevious: () -> Unit,
): Modifier {
    // Playback state updates can recompose the bar while a finger is down.
    // Keep the gesture coroutine alive through those updates while still
    // dispatching to the latest controller callbacks when the drag finishes.
    val currentOnNext by rememberUpdatedState(onNext)
    val currentOnPrevious by rememberUpdatedState(onPrevious)
    return pointerInput(Unit) {
        val threshold = TRACK_SWIPE_THRESHOLD.toPx()
        var totalDrag = 0f
        detectHorizontalDragGestures(
            onDragStart = { totalDrag = 0f },
            onDragCancel = { totalDrag = 0f },
            onDragEnd = {
                when {
                    totalDrag <= -threshold -> currentOnNext()
                    totalDrag >= threshold -> currentOnPrevious()
                }
                totalDrag = 0f
            },
            onHorizontalDrag = { change, amount ->
                change.consume()
                totalDrag += amount
            },
        )
    }
}

/** Frosted mini player that rides just above the floating tab bar. */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun MiniPlayer(
    song: Song,
    isPlaying: Boolean,
    isLoading: Boolean,
    hazeState: HazeState,
    positionMs: Long = 0L,
    durationMs: Long = 0L,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    hazeStyle: dev.chrisbanes.haze.HazeStyle = HazeMaterials.thin(MaterialTheme.colorScheme.surface),
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val useGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val haptics = rememberHaptics()
    val interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "miniPlayerScale",
    )
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .padding(horizontal = 8.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = 12.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.5f),
                spotColor = Color.Black.copy(alpha = 0.5f)
            )
            .clip(shape)
            .then(
                if (reduceDynamicBlur) {
                    Modifier.background(MaterialTheme.colorScheme.surface)
                } else if (useGlass) {
                    Modifier.optimizedHazeEffect(
                        state = hazeState,
                        style = hazeStyle,
                    )
                } else {
                    Modifier.optimizedHazeEffect(
                        state = hazeState,
                        style = HazeMaterials.regular(MaterialTheme.colorScheme.surface),
                    )
                },
            )
            .border(0.5.dp, Color.White.copy(alpha = 0.10f), shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onExpand,
            )
            .miniPlayerTrackSwipe(
                onNext = {
                    haptics.play(Haptic.SkipNext)
                    onNext()
                },
                onPrevious = {
                    haptics.play(Haptic.SkipPrevious)
                    onPrevious()
                },
            ),
    ) {
        val artScale by animateFloatAsState(
            targetValue = if (isPlaying) 1f else 0.90f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "miniPlayerArtScale",
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 12.dp,
                    vertical = 8.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = song.artworkAt(ROW_ART_PX),
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .graphicsLayer {
                        scaleX = artScale
                        scaleY = artScale
                    }
                    .clip(RoundedCornerShape(ART_CORNER))
                    .thumbnailBorder(RoundedCornerShape(ART_CORNER))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                ExplicitSongTitle(
                    song = song,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val prevInteractionSource = remember { MutableInteractionSource() }
            val isPrevPressed by prevInteractionSource.collectIsPressedAsState()
            val prevScale by animateFloatAsState(
                targetValue = if (isPrevPressed) 0.82f else 1f,
                animationSpec = spring(),
                label = "prevScale",
            )
            IconButton(
                onClick = {
                    haptics.play(Haptic.SkipPrevious)
                    onPrevious()
                },
                interactionSource = prevInteractionSource,
                modifier = Modifier
                    .size(GLYPH_SLOT)
                    .graphicsLayer {
                        scaleX = prevScale
                        scaleY = prevScale
                    },
            ) {
                Icon(
                    imageVector = Icons.Rounded.FastRewind,
                    contentDescription = stringResource(R.string.widget_previous),
                    tint = AccentRed.copy(alpha = 0.6f),
                    modifier = Modifier.size(24.dp),
                )
            }

            if (isLoading) {
                Box(Modifier.size(GLYPH_SLOT), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        color = AccentRed,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(SPINNER_SIZE),
                    )
                }
            } else {
                val buttonInteractionSource = remember { MutableInteractionSource() }
                val isButtonPressed by buttonInteractionSource.collectIsPressedAsState()
                val buttonScale by animateFloatAsState(
                    targetValue = if (isButtonPressed) 0.82f else 1f,
                    animationSpec = spring(),
                    label = "playPauseScale",
                )
                IconButton(
                    onClick = {
                        haptics.play(if (isPlaying) Haptic.Pause else Haptic.Resume)
                        onPlayPause()
                    },
                    interactionSource = buttonInteractionSource,
                    modifier = Modifier
                        .size(GLYPH_SLOT)
                        .graphicsLayer {
                            scaleX = buttonScale
                            scaleY = buttonScale
                        },
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
                        tint = AccentRed,
                        modifier = Modifier.size(GLYPH_SIZE),
                    )
                }
            }
            Spacer(Modifier.width(TRANSPORT_GAP))
            val nextInteractionSource = remember { MutableInteractionSource() }
            val isNextPressed by nextInteractionSource.collectIsPressedAsState()
            val nextScale by animateFloatAsState(
                targetValue = if (isNextPressed) 0.82f else 1f,
                animationSpec = spring(),
                label = "nextScale",
            )
            IconButton(
                onClick = {
                    haptics.play(Haptic.SkipNext)
                    onNext()
                },
                interactionSource = nextInteractionSource,
                modifier = Modifier
                    .size(GLYPH_SLOT)
                    .graphicsLayer {
                        scaleX = nextScale
                        scaleY = nextScale
                    },
            ) {
                Icon(
                    imageVector = BitChordIcons.SkipNext,
                    contentDescription = stringResource(R.string.widget_next),
                    tint = AccentRed,
                    modifier = Modifier.size(GLYPH_SIZE),
                )
            }
        }

        if (durationMs > 0) {
            val progress = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(progress)
                    .height(2.5.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(AccentRed.copy(alpha = 0.5f), AccentRed)
                        )
                    )
            )
        }
    }
}
