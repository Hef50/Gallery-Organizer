package com.galleryorganizer.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.galleryorganizer.data.db.entity.TagKind
import com.galleryorganizer.ui.theme.TagPalette

/**
 * The icon and colour a tag kind is drawn with.
 *
 * Kept in one place so a Person tag looks identical in the picker, the tag manager, the
 * viewer's chips and the search filters. A tag the user has coloured by hand always wins:
 * the kind colour is a default, not a rule.
 */
val TagKind.icon: ImageVector
    get() = when (this) {
        TagKind.Person -> Icons.Rounded.Person
        TagKind.Place -> Icons.Rounded.Place
        TagKind.Event -> Icons.Rounded.Event
        TagKind.Thing -> Icons.Rounded.Category
        TagKind.Note -> Icons.Rounded.Sell
    }

val TagKind.accent: Color
    get() = when (this) {
        TagKind.Person -> TagPalette.Person
        TagKind.Place -> TagPalette.Place
        TagKind.Event -> TagPalette.Event
        TagKind.Thing -> TagPalette.Thing
        TagKind.Note -> TagPalette.Note
    }

/** What the picker offers when someone is about to create a tag of this kind. */
val TagKind.hint: String
    get() = when (this) {
        TagKind.Person -> "Who is in it"
        TagKind.Place -> "Where it was taken"
        TagKind.Event -> "What was happening"
        TagKind.Thing -> "What is in it"
        TagKind.Note -> "Anything else"
    }

/** A tag's colour: its own if it has one, otherwise its kind's. */
fun tagAccent(color: Int?, kind: TagKind): Color = color?.let(::Color) ?: kind.accent
