package io.github.voidcast00.faceflush.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.voidcast00.faceflush.R
import io.github.voidcast00.faceflush.ui.theme.SectionLabel
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * What the lab measured for Max privacy (docs/DIARY.md, 2026-10-12): matches out of 100 by a
 * strong AI the cloak never saw (ArcFace), after Instagram-style compression, on 150 test faces.
 * Strength 0 is an unprotected photo.
 */
object LabStats {
    val strengths = listOf(8, 16, 24)
    private val brightness = listOf(0 to 99, 8 to 87, 16 to 14, 24 to 3)
    private val rainbow = listOf(0 to 99, 8 to 90, 16 to 39, 24 to 18)
    const val QUICK_CLOAK = 99

    /** Measured value at the lab's strengths, straight-line estimate in between. */
    fun recognised(strength: Float, rainbow: Boolean): Int {
        val points = if (rainbow) this.rainbow else brightness
        val s = strength.coerceIn(points.first().first.toFloat(), points.last().first.toFloat())
        val (a, b) = points.zipWithNext().first { (_, hi) -> s <= hi.first }
        val t = (s - a.first) / (b.first - a.first)
        return (a.second + t * (b.second - a.second)).roundToInt()
    }
}

private fun picture(strength: Int, rainbow: Boolean) = when (strength) {
    8 -> if (rainbow) R.drawable.stat_r8 else R.drawable.stat_b8
    16 -> if (rainbow) R.drawable.stat_r16 else R.drawable.stat_b16
    else -> if (rainbow) R.drawable.stat_r24 else R.drawable.stat_b24
}

/**
 * [total] small squares in rows of [columns]; the first [count] are lit and flicker now and then,
 * like the glitch band on the home screen. Rainbow lights them in colours.
 */
@Composable
fun RecognisedGrid(count: Int, modifier: Modifier = Modifier, rainbow: Boolean = false, total: Int = 100, columns: Int = 10) {
    val colors = MaterialTheme.colorScheme
    val tick = rememberGlitchTick()
    val rows = (total + columns - 1) / columns
    Canvas(modifier.aspectRatio(columns / rows.toFloat())) {
        val pitch = size.width / columns
        val gap = pitch * 0.18f
        val rng = Random(tick)
        for (i in 0 until total) {
            val lit = i < count
            val base = when {
                !lit -> colors.surfaceContainerHighest
                rainbow -> Color.hsv(i * 37f % 360f, 0.55f, 0.95f)
                else -> colors.primary
            }
            val alpha = if (lit && rng.nextFloat() < 0.12f) 0.45f else 1f
            drawRoundRect(
                base.copy(alpha = alpha),
                topLeft = Offset(i % columns * pitch, i / columns * pitch),
                size = Size(pitch - gap, pitch - gap),
                cornerRadius = CornerRadius(gap),
            )
        }
    }
}

/** One row of 20 squares (5 faces each), for a quick read next to a setting. */
@Composable
fun StatStrip(count: Int, modifier: Modifier = Modifier, rainbow: Boolean = false) {
    RecognisedGrid((count / 5f).roundToInt(), modifier, rainbow, total = 20, columns = 20)
}

/**
 * The explainer's results: Light / Medium / Strong side by side, each with the test painting at that
 * strength, a 100-face grid and the number, switchable between brightness only and rainbow style.
 */
@Composable
fun StrengthStats(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    var rainbow by rememberSaveable { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainer, modifier = modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !rainbow, onClick = { rainbow = false }, label = { Text(stringResource(R.string.stats_brightness)) })
                FilterChip(selected = rainbow, onClick = { rainbow = true }, label = { Text(stringResource(R.string.stats_rainbow)) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LabStats.strengths.forEach { strength ->
                    val count = LabStats.recognised(strength.toFloat(), rainbow)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.stats_strength, strength).uppercase(),
                            style = SectionLabel,
                            color = colors.onSurfaceVariant,
                        )
                        Image(
                            painterResource(picture(strength, rainbow)),
                            contentDescription = stringResource(R.string.stats_picture),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
                        )
                        RecognisedGrid(count, Modifier.fillMaxWidth(), rainbow)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                count.toString(),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Black,
                                color = colors.primary,
                            )
                            Text(
                                stringResource(R.string.stats_of_100),
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(start = 2.dp, bottom = 5.dp),
                            )
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RecognisedGrid(1, Modifier.size(12.dp), rainbow, total = 1, columns = 1)
                Text(
                    stringResource(R.string.stats_lit),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.stats_quick).uppercase(),
                        style = SectionLabel,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${LabStats.QUICK_CLOAK}${stringResource(R.string.stats_of_100)}", style = MaterialTheme.typography.labelLarge, color = colors.primary)
                }
                StatStrip(LabStats.QUICK_CLOAK, Modifier.fillMaxWidth())
            }
            Text(
                stringResource(R.string.stats_footnote),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}
