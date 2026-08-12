package com.galleryorganizer.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * The app's motion vocabulary.
 *
 * Everything that moves uses a **spring**, not a duration. A duration-based animation has
 * to finish before it can be told to do something else, so a gesture that changes
 * direction mid-flight either stutters or fights the animation. A spring carries its
 * current velocity into a new target, which is why a flick, a pinch and a drag-to-dismiss
 * all feel like they are made of the same material.
 *
 * The two families come from Material 3 Expressive:
 *
 * - **Spatial** — anything that changes position or size. Slightly under-damped, so it
 *   settles with a hint of overshoot. That overshoot is the whole point: it reads as
 *   physical rather than mechanical.
 * - **Effects** — anything that changes colour or alpha. Critically damped, always. A
 *   colour that bounces past its target and back looks like a bug, because nothing in the
 *   physical world does that.
 */
object Motion {

    // --- Spatial: position, size, offset, scale ---------------------------------------

    /** Small, frequent movements — a chip settling, a checkmark appearing. */
    fun <T> fastSpatial(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.75f, stiffness = 1400f)

    /** The default for most movement. */
    fun <T> spatial(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.8f, stiffness = 600f)

    /** Large travel — a sheet arriving, the viewer opening. */
    fun <T> slowSpatial(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.85f, stiffness = 260f)

    /**
     * Deliberately bouncy. Reserved for moments that should feel *good* — a tag landing on
     * a few hundred photos, the grid re-flowing under a pinch. Used sparingly; overshoot
     * everywhere is exhausting rather than delightful.
     */
    fun <T> expressiveSpatial(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.6f, stiffness = 500f)

    // --- Effects: alpha, colour, elevation ---------------------------------------------

    fun <T> fastEffects(): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 3800f)

    fun <T> effects(): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1600f)

    fun <T> slowEffects(): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 800f)

    // --- Specialised --------------------------------------------------------------------

    /**
     * Bounds animation for the grid→viewer hero transition.
     *
     * Barely any overshoot: a photo growing to fill the screen and then wobbling looks
     * cheap, and at full-bleed the overshoot would show as a visible edge gap.
     */
    val heroBounds: FiniteAnimationSpec<androidx.compose.ui.geometry.Rect> =
        spring(dampingRatio = 0.9f, stiffness = 380f)

    val heroSize: FiniteAnimationSpec<IntSize> = spring(dampingRatio = 0.9f, stiffness = 380f)
    val heroOffset: FiniteAnimationSpec<IntOffset> = spring(dampingRatio = 0.9f, stiffness = 380f)

    /**
     * Settling a pinch-zoom back to its bounds. Slightly stiffer than the default so the
     * image feels taut under the finger rather than elastic.
     */
    fun <T> zoomSettle(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.9f, stiffness = 900f)

    /**
     * The one place a duration is right: a cross-fade between two *different* images has
     * no physical analogue, so a spring's velocity carry-over means nothing. Emphasised
     * decelerate, per Material.
     */
    fun <T> crossfade(durationMillis: Int = 220): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = EmphasizedDecelerate)

    val EmphasizedDecelerate: CubicBezierEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate: CubicBezierEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
}
