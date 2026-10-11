package io.github.voidcast00.faceflush.effects

import androidx.annotation.StringRes
import io.github.voidcast00.faceflush.R

enum class RegionMode(@StringRes val label: Int) {
    FACES(R.string.region_faces),
    WHOLE_IMAGE(R.string.region_whole),
    MANUAL(R.string.region_manual),
}

enum class Strength(@StringRes val label: Int) {
    LIGHT(R.string.strength_light),
    MEDIUM(R.string.strength_medium),
    STRONG(R.string.strength_strong),
}

/**
 * One adjustable setting. With [options] set it is a choice (value = option index) shown as
 * buttons; otherwise it is a slider from [min] to [max].
 */
data class ParamSpec(
    val key: String,
    @StringRes val label: Int,
    val min: Float = 0f,
    val max: Float = 1f,
    val step: Float = 1f,
    val options: List<Int>? = null,
)

object Params {
    const val COVERAGE = "coverage"
    const val SHAPE = "shape"
    const val BLOCKS = "blocks"
    const val BLUR_RADIUS = "blurRadius"
    const val BLUR_PASSES = "blurPasses"
    const val COLOR = "color"
    const val DENSITY = "density"
    const val CLUSTER = "cluster"
    const val PALETTE = "palette"
    const val NOISE = "noise"
    const val SCANLINES = "scanlines"
    const val THICKNESS = "thickness"
    const val OPACITY = "opacity"
    const val STYLE = "style"
    const val VISIBILITY = "visibility"
    const val MORPH = "morph"
}

private val coverageSpec = ParamSpec(Params.COVERAGE, R.string.param_coverage, 1f, 1.8f, 0.05f)
private val shapeSpec = ParamSpec(
    Params.SHAPE, R.string.param_shape,
    options = listOf(R.string.option_oval, R.string.option_rectangle),
)

enum class Technique(
    @StringRes val title: Int,
    @StringRes val description: Int,
    val regionModes: List<RegionMode>,
    val defaultRegion: RegionMode,
    val params: List<ParamSpec>,
    /** Values that don't change with strength (choices such as shape or colour). */
    private val defaults: Map<String, Float>,
    private val presets: Map<Strength, Map<String, Float>>,
) {
    PIXELATE(
        R.string.technique_pixelate, R.string.technique_pixelate_desc,
        RegionMode.entries, RegionMode.FACES,
        listOf(ParamSpec(Params.BLOCKS, R.string.param_blocks, 3f, 32f), coverageSpec, shapeSpec),
        defaults = mapOf(Params.SHAPE to 0f),
        presets = mapOf(
            Strength.LIGHT to mapOf(Params.BLOCKS to 16f, Params.COVERAGE to 1.1f),
            Strength.MEDIUM to mapOf(Params.BLOCKS to 10f, Params.COVERAGE to 1.25f),
            Strength.STRONG to mapOf(Params.BLOCKS to 6f, Params.COVERAGE to 1.4f),
        ),
    ),
    BLUR(
        R.string.technique_blur, R.string.technique_blur_desc,
        RegionMode.entries, RegionMode.FACES,
        listOf(
            ParamSpec(Params.BLUR_RADIUS, R.string.param_blur_radius, 1f, 30f),
            ParamSpec(Params.BLUR_PASSES, R.string.param_blur_passes, 1f, 4f),
            coverageSpec, shapeSpec,
        ),
        defaults = mapOf(Params.SHAPE to 0f),
        presets = mapOf(
            Strength.LIGHT to mapOf(Params.BLUR_RADIUS to 5f, Params.BLUR_PASSES to 1f, Params.COVERAGE to 1.1f),
            Strength.MEDIUM to mapOf(Params.BLUR_RADIUS to 10f, Params.BLUR_PASSES to 2f, Params.COVERAGE to 1.25f),
            Strength.STRONG to mapOf(Params.BLUR_RADIUS to 18f, Params.BLUR_PASSES to 3f, Params.COVERAGE to 1.4f),
        ),
    ),
    MASK(
        R.string.technique_mask, R.string.technique_mask_desc,
        listOf(RegionMode.FACES, RegionMode.MANUAL), RegionMode.FACES,
        listOf(
            ParamSpec(
                Params.COLOR, R.string.param_color,
                options = listOf(R.string.option_black, R.string.option_white, R.string.option_gray),
            ),
            coverageSpec, shapeSpec,
        ),
        defaults = mapOf(Params.COLOR to 0f, Params.SHAPE to 0f),
        presets = mapOf(
            Strength.LIGHT to mapOf(Params.COVERAGE to 1.0f),
            Strength.MEDIUM to mapOf(Params.COVERAGE to 1.2f),
            Strength.STRONG to mapOf(Params.COVERAGE to 1.45f),
        ),
    ),
    DEAD_PIXELS(
        R.string.technique_dead_pixels, R.string.technique_dead_pixels_desc,
        RegionMode.entries, RegionMode.FACES,
        listOf(
            ParamSpec(Params.DENSITY, R.string.param_density, 0.5f, 30f, 0.5f),
            ParamSpec(Params.CLUSTER, R.string.param_cluster, 1f, 8f),
            ParamSpec(Params.NOISE, R.string.param_noise, 0f, 100f),
            ParamSpec(
                Params.PALETTE, R.string.param_palette,
                options = listOf(R.string.option_black_white, R.string.option_rainbow, R.string.option_mixed),
            ),
            ParamSpec(
                Params.SCANLINES, R.string.param_scanlines,
                options = listOf(R.string.option_off, R.string.option_on),
            ),
            coverageSpec, shapeSpec,
        ),
        defaults = mapOf(Params.PALETTE to 2f, Params.SHAPE to 0f),
        presets = mapOf(
            Strength.LIGHT to mapOf(
                Params.DENSITY to 2f, Params.CLUSTER to 1f, Params.NOISE to 15f,
                Params.SCANLINES to 0f, Params.COVERAGE to 1.1f,
            ),
            Strength.MEDIUM to mapOf(
                Params.DENSITY to 6f, Params.CLUSTER to 2f, Params.NOISE to 35f,
                Params.SCANLINES to 0f, Params.COVERAGE to 1.2f,
            ),
            Strength.STRONG to mapOf(
                Params.DENSITY to 15f, Params.CLUSTER to 3f, Params.NOISE to 60f,
                Params.SCANLINES to 1f, Params.COVERAGE to 1.3f,
            ),
        ),
    ),
    LANDMARKS(
        R.string.technique_landmarks, R.string.technique_landmarks_desc,
        listOf(RegionMode.FACES, RegionMode.MANUAL), RegionMode.FACES,
        listOf(
            ParamSpec(Params.THICKNESS, R.string.param_thickness, 15f, 120f),
            ParamSpec(Params.OPACITY, R.string.param_opacity, 20f, 100f),
            ParamSpec(
                Params.STYLE, R.string.param_style,
                options = listOf(R.string.option_solid, R.string.option_checker, R.string.option_noise),
            ),
        ),
        defaults = mapOf(Params.STYLE to 1f),
        presets = mapOf(
            Strength.LIGHT to mapOf(Params.THICKNESS to 35f, Params.OPACITY to 70f),
            Strength.MEDIUM to mapOf(Params.THICKNESS to 60f, Params.OPACITY to 90f),
            Strength.STRONG to mapOf(Params.THICKNESS to 90f, Params.OPACITY to 100f),
        ),
    ),
    INVISIBLE(
        R.string.technique_invisible, R.string.technique_invisible_desc,
        listOf(RegionMode.FACES, RegionMode.MANUAL), RegionMode.FACES,
        listOf(
            // Cloak strength in 1/255 steps; 0 turns the cloak off
            ParamSpec(Params.VISIBILITY, R.string.param_visibility, 0f, 16f),
            // Kept for those who want it, but off by default: the lab found it barely works
            // (strong AI still matched 97% at 1.0) while visibly changing the face
            ParamSpec(Params.MORPH, R.string.param_morph, 0f, 1.5f, 0.1f),
        ),
        defaults = mapOf(Params.MORPH to 0f),
        // 8 and 16 are the strengths measured in the lab (see docs/DIARY.md)
        presets = mapOf(
            Strength.LIGHT to mapOf(Params.VISIBILITY to 4f),
            Strength.MEDIUM to mapOf(Params.VISIBILITY to 8f),
            Strength.STRONG to mapOf(Params.VISIBILITY to 16f),
        ),
    );

    /** Full parameter set for [strength], keeping any choices already made in [current]. */
    fun paramsFor(strength: Strength, current: Map<String, Float> = emptyMap()): Map<String, Float> {
        val choices = params.filter { it.options != null }.mapNotNull { spec ->
            current[spec.key]?.let { spec.key to it }
        }
        return defaults + choices + presets.getValue(strength)
    }
}
