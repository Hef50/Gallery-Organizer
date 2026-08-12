package com.galleryorganizer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shapes get rounder than Material's defaults. Rounder corners read as softer and more
 * considered, and they matter here because almost every surface in this app sits directly
 * on top of photographs — a sharp corner against a photo looks like a crop, a round one
 * looks like a card.
 */
private val GalleryShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

/**
 * Roboto, but tuned. The stock Material scale is set for dense product UI; a gallery wants
 * fewer, larger, quieter labels. Display and headline sizes get negative tracking, which
 * is what stops large text looking like enlarged body copy, and body text gets a little
 * more line height so captions and descriptions breathe.
 */
private val GalleryTypography = Typography().run {
    val sans = FontFamily.SansSerif
    copy(
        displayLarge = displayLarge.copy(fontFamily = sans, letterSpacing = (-1.0).sp, fontWeight = FontWeight.Medium),
        displayMedium = displayMedium.copy(fontFamily = sans, letterSpacing = (-0.8).sp, fontWeight = FontWeight.Medium),
        displaySmall = displaySmall.copy(fontFamily = sans, letterSpacing = (-0.5).sp, fontWeight = FontWeight.Medium),
        headlineLarge = headlineLarge.copy(fontFamily = sans, letterSpacing = (-0.5).sp, fontWeight = FontWeight.SemiBold),
        headlineMedium = headlineMedium.copy(fontFamily = sans, letterSpacing = (-0.4).sp, fontWeight = FontWeight.SemiBold),
        headlineSmall = headlineSmall.copy(fontFamily = sans, letterSpacing = (-0.3).sp, fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontFamily = sans, letterSpacing = (-0.3).sp, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontFamily = sans, letterSpacing = (-0.1).sp, fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontFamily = sans, fontWeight = FontWeight.Medium),
        bodyLarge = bodyLarge.copy(fontFamily = sans, lineHeight = 25.sp),
        bodyMedium = bodyMedium.copy(fontFamily = sans, lineHeight = 21.sp),
        bodySmall = bodySmall.copy(fontFamily = sans, lineHeight = 17.sp),
        labelLarge = labelLarge.copy(fontFamily = sans, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
        labelMedium = labelMedium.copy(fontFamily = sans, fontWeight = FontWeight.Medium),
        labelSmall = labelSmall.copy(fontFamily = sans, fontWeight = FontWeight.Medium),
    )
}

/** Numbers that should not jitter as they count up — item counts, zoom levels, durations. */
val TabularFigures: TextStyle = TextStyle(fontFeatureSettings = "tnum")

/** True when the current theme is the dark one, for the handful of places that need it. */
val LocalIsDarkTheme = staticCompositionLocalOf { true }

@Composable
fun GalleryOrganizerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /**
     * Off by default, and that is a deliberate choice rather than an oversight. Dynamic
     * colour derives the whole palette from the wallpaper, which means the app's identity
     * changes every time the user changes their background — and against photographs, a
     * randomly-sampled accent is as likely to clash as to complement. The curated palette
     * stays out of the way of the pictures. Users who want their wallpaper's colour can
     * turn it on in Settings.
     */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> GalleryDarkColors
        else -> GalleryLightColors
    }

    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        // Material 3 1.4.0 ships MaterialExpressiveTheme and MotionScheme but marks them
        // `internal`, so the expressive motion scheme cannot be handed to the built-in
        // components yet. [Motion] encodes the same spring values by hand instead, and
        // every surface this app draws itself uses it — which is nearly all of them.
        MaterialTheme(
            colorScheme = colors,
            shapes = GalleryShapes,
            typography = GalleryTypography,
            content = content,
        )
    }
}
