package io.github.voidcast00.faceflush.effects

import kotlin.math.max
import kotlin.math.roundToInt

object EffectRenderer {
    private val maskColors = intArrayOf(argb(0, 0, 0), argb(255, 255, 255), argb(128, 128, 128))

    /**
     * Applies [technique] to every region in [regions].
     *
     * Regions are in source-image coordinates; [scale] maps them onto [buf] (1 for the full-size
     * export, smaller for the preview). [seed] keeps the random effects stable between renders.
     */
    fun render(
        buf: PixelBuffer,
        technique: Technique,
        params: Map<String, Float>,
        regions: List<Region>,
        scale: Float,
        seed: Long,
        invisible: InvisibleAssets? = null,
    ) {
        fun p(key: String) = params[key] ?: 0f

        regions.forEachIndexed { index, region ->
            val regionSeed = seed + index * 7919L
            val sourceBox = if (region.wholeImage) region.box else region.box.expanded(p(Params.COVERAGE))
            val box = sourceBox.scaled(scale)
            val oval = !region.wholeImage && p(Params.SHAPE) == 0f
            val shape = Shape.of(box, oval, buf.width, buf.height) ?: return@forEachIndexed

            when (technique) {
                Technique.PIXELATE ->
                    pixelate(buf, shape, max(2, (box.width / max(p(Params.BLOCKS), 1f)).roundToInt()))

                Technique.BLUR -> {
                    val radius = max(1, (box.width * p(Params.BLUR_RADIUS) / 100f).roundToInt())
                    blur(buf, shape, radius, max(1, p(Params.BLUR_PASSES).roundToInt()))
                }

                Technique.MASK -> fill(buf, shape, maskColors[p(Params.COLOR).toInt().coerceIn(0, 2)])

                Technique.DEAD_PIXELS -> deadPixels(
                    buf, shape, box,
                    sourceArea = sourceBox.width * sourceBox.height,
                    scale = scale,
                    seed = regionSeed,
                    densityPercent = p(Params.DENSITY),
                    clusterPx = max(1f, p(Params.CLUSTER)),
                    palette = p(Params.PALETTE).toInt(),
                    noise = p(Params.NOISE),
                    scanlines = p(Params.SCANLINES) >= 1f,
                )

                Technique.LANDMARKS -> landmarkBars(
                    buf,
                    (region.landmarks ?: Landmarks.estimate(region.box)).scaled(scale),
                    thicknessPercent = p(Params.THICKNESS),
                    opacity = p(Params.OPACITY),
                    style = p(Params.STYLE).toInt(),
                    seed = regionSeed,
                )

                Technique.INVISIBLE -> {
                    val lm = (region.landmarks ?: Landmarks.estimate(region.box)).scaled(scale)
                    invisible?.morph?.let { morph(buf, lm, it, p(Params.MORPH)) }
                    invisible?.cloak?.let { cloak(buf, lm, it, p(Params.VISIBILITY) / 255f) }
                }
            }
        }
    }
}
