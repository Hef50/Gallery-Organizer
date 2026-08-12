package com.galleryorganizer.permissions

import android.os.Build
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaPermissionStateTest {

    @Test
    fun `nothing granted is no access`() {
        val state = MediaPermissionState(images = false, video = false, userSelected = false)
        assertThat(state.access).isEqualTo(MediaAccess.None)
        assertThat(state.canIndex).isFalse()
    }

    @Test
    fun `user selected alone is a partial grant`() {
        val state = MediaPermissionState(images = false, video = false, userSelected = true)
        assertThat(state.access).isEqualTo(MediaAccess.Partial)
        assertThat(state.canIndex).isTrue()
    }

    @Test
    fun `both full permissions is full access`() {
        val state = MediaPermissionState(images = true, video = true, userSelected = false)
        assertThat(state.access).isEqualTo(MediaAccess.Full)
        assertThat(state.isLopsided).isFalse()
    }

    @Test
    fun `a full grant outranks a concurrent user-selected grant`() {
        // Android hands back READ_MEDIA_VISUAL_USER_SELECTED as granted alongside a full
        // grant in some OEM builds. Full access must win, or the UI would nag about a
        // partial selection that does not exist.
        val state = MediaPermissionState(images = true, video = true, userSelected = true)
        assertThat(state.access).isEqualTo(MediaAccess.Full)
    }

    @Test
    fun `images without video is full but lopsided`() {
        val state = MediaPermissionState(images = true, video = false, userSelected = false)
        assertThat(state.access).isEqualTo(MediaAccess.Full)
        assertThat(state.isLopsided).isTrue()
    }

    @Test
    fun `video without images is full but lopsided`() {
        val state = MediaPermissionState(images = false, video = true, userSelected = false)
        assertThat(state.access).isEqualTo(MediaAccess.Full)
        assertThat(state.isLopsided).isTrue()
    }

    @Test
    fun `denied constant is the empty state`() {
        assertThat(MediaPermissionState.Denied.access).isEqualTo(MediaAccess.None)
    }

    @Test
    fun `android 14 requests the user-selected permission alongside the full ones`() {
        val requested = MediaPermissions.requestedPermissions(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        assertThat(requested.toList()).containsExactly(
            MediaPermissions.READ_MEDIA_IMAGES,
            MediaPermissions.READ_MEDIA_VIDEO,
            MediaPermissions.READ_MEDIA_VISUAL_USER_SELECTED,
        )
    }

    @Test
    fun `android 13 requests only the two full permissions`() {
        // READ_MEDIA_VISUAL_USER_SELECTED does not exist on API 33 and requesting it
        // there would be silently ignored at best.
        val requested = MediaPermissions.requestedPermissions(Build.VERSION_CODES.TIRAMISU)
        assertThat(requested.toList()).containsExactly(
            MediaPermissions.READ_MEDIA_IMAGES,
            MediaPermissions.READ_MEDIA_VIDEO,
        )
    }
}
