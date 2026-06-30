package com.dangerfield.cards.libraries.ui

import androidx.compose.runtime.Composable

/**
 * One image the user picked from their photo library, already downscaled and
 * JPEG-compressed by the platform picker so a handful of them stay well under
 * the crash reporter's attachment cap.
 *
 * [bytes] is the source of truth — what we attach to a feedback / bug report.
 * Deliberately a plain class (not a `data class`): the feedback state holds a
 * list of these, and we only ever add or drop whole instances, so identity
 * equality is both correct and cheap. A `data class` would do a per-byte
 * comparison on every state emission.
 */
class PickedImage(val bytes: ByteArray)

/** How many screenshots a single feedback / bug report may carry. */
const val MAX_FEEDBACK_SCREENSHOTS = 3

interface ImagePicker {
    /** Opens the system photo library. Cancelling delivers an empty list. */
    fun launch()
}

/**
 * Remembers a system photo picker capped at [maxImages] selections. [onResult]
 * is delivered on the main thread with the downscaled JPEG bytes of every image
 * the user picked (empty if they cancelled). Each platform resizes to a long
 * edge of ~1600px at JPEG ~0.7, so a few screenshots add up to a few hundred KB
 * rather than tens of MB. Needs no runtime permission on either platform — both
 * use the out-of-process system picker.
 */
@Composable
expect fun rememberImagePicker(
    maxImages: Int,
    onResult: (List<PickedImage>) -> Unit,
): ImagePicker
