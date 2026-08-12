package com.galleryorganizer.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The palette.
 *
 * A photo library is almost entirely *other people's colour*. Every hue the chrome
 * introduces competes with the photographs, so this palette is built from near-neutrals
 * with a single restrained accent, and the accent is only ever used to mean "you did
 * this" — selection, an applied tag, the active filter.
 *
 * Dark mode is the primary target and is nearly black rather than Material's default dark
 * grey. On the OLED panel this phone has, a true-black ground makes a photograph look like
 * it is lit from behind; a grey one makes it look like a print on card. The surfaces step
 * up in small, even increments so depth reads without any surface ever becoming
 * "a light box in a dark room".
 */
private val Ink = Color(0xFF08080A)
private val Ink1 = Color(0xFF101014)
private val Ink2 = Color(0xFF16161B)
private val Ink3 = Color(0xFF1E1E24)
private val Ink4 = Color(0xFF26262E)

private val Paper = Color(0xFFFBFAF9)
private val Paper1 = Color(0xFFFFFFFF)
private val Paper2 = Color(0xFFF3F2F0)
private val Paper3 = Color(0xFFEBEAE7)

/**
 * A cool, slightly desaturated azure. Deliberately not a saturated brand blue: at full
 * chroma an accent competes with skin tones and skies, which is most of a camera roll.
 */
private val Azure = Color(0xFF7AA2F7)
private val AzureDeep = Color(0xFF2E5EAA)
private val AzureInk = Color(0xFF00224D)
private val AzureWash = Color(0xFFDCE6FF)

/** Reserved strictly for destructive confirmation. */
private val Rose = Color(0xFFFF8A8A)
private val RoseDeep = Color(0xFFB3261E)

val GalleryDarkColors = darkColorScheme(
    primary = Azure,
    onPrimary = AzureInk,
    primaryContainer = AzureDeep,
    onPrimaryContainer = Color(0xFFE6EEFF),
    inversePrimary = AzureDeep,

    secondary = Color(0xFFB9C2D6),
    onSecondary = Color(0xFF232A38),
    secondaryContainer = Ink4,
    onSecondaryContainer = Color(0xFFDCE2EE),

    tertiary = Color(0xFFD9C08A),
    onTertiary = Color(0xFF3A2E10),
    tertiaryContainer = Color(0xFF52431F),
    onTertiaryContainer = Color(0xFFF6E7C4),

    background = Ink,
    onBackground = Color(0xFFEDEDF2),
    surface = Ink,
    onSurface = Color(0xFFEDEDF2),
    surfaceVariant = Ink3,
    onSurfaceVariant = Color(0xFFA9A9B6),
    surfaceTint = Azure,

    surfaceContainerLowest = Ink,
    surfaceContainerLow = Ink1,
    surfaceContainer = Ink2,
    surfaceContainerHigh = Ink3,
    surfaceContainerHighest = Ink4,

    outline = Color(0xFF4A4A56),
    outlineVariant = Color(0xFF2A2A32),

    error = Rose,
    onError = Color(0xFF410002),
    errorContainer = Color(0xFF6E1F1A),
    onErrorContainer = Color(0xFFFFDAD6),

    scrim = Color(0xFF000000),
)

val GalleryLightColors = lightColorScheme(
    primary = AzureDeep,
    onPrimary = Color.White,
    primaryContainer = AzureWash,
    onPrimaryContainer = AzureInk,
    inversePrimary = Azure,

    secondary = Color(0xFF565E71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE2F2),
    onSecondaryContainer = Color(0xFF141B2B),

    tertiary = Color(0xFF7A5F1E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF7E4B8),
    onTertiaryContainer = Color(0xFF271900),

    background = Paper,
    onBackground = Color(0xFF1A1A1D),
    surface = Paper,
    onSurface = Color(0xFF1A1A1D),
    surfaceVariant = Paper3,
    onSurfaceVariant = Color(0xFF5A5A63),
    surfaceTint = AzureDeep,

    surfaceContainerLowest = Paper1,
    surfaceContainerLow = Color(0xFFF8F7F5),
    surfaceContainer = Paper2,
    surfaceContainerHigh = Paper3,
    surfaceContainerHighest = Color(0xFFE4E3E0),

    outline = Color(0xFF8B8B93),
    outlineVariant = Color(0xFFD2D1CE),

    error = RoseDeep,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    scrim = Color(0xFF000000),
)

/**
 * Colour for a tag kind. Muted on purpose — a tag list should read as a list, not as a
 * bag of sweets, and these sit next to photographs.
 */
object TagPalette {
    val Person = Color(0xFF7AA2F7)
    val Place = Color(0xFF7BC49A)
    val Event = Color(0xFFD9A86C)
    val Thing = Color(0xFFB99BD8)
    val Note = Color(0xFF9AA0AE)

    /** Options offered when the user colours a tag by hand. */
    val Swatches = listOf(
        Color(0xFF7AA2F7), Color(0xFF7BC49A), Color(0xFFD9A86C), Color(0xFFB99BD8),
        Color(0xFFE8899A), Color(0xFF6FC7CE), Color(0xFFC9C06A), Color(0xFF9AA0AE),
    )
}
