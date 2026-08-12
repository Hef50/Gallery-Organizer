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
            MediaPermissions.ACCESS_MEDIA_LOCATION,
        )
    }

    @Test
    fun `android 13 requests the two full permissions plus media location`() {
        // READ_MEDIA_VISUAL_USER_SELECTED does not exist on API 33 and requesting it
        // there would be silently ignored at best. ACCESS_MEDIA_LOCATION does.
        val requested = MediaPermissions.requestedPermissions(Build.VERSION_CODES.TIRAMISU)
        assertThat(requested.toList()).containsExactly(
            MediaPermissions.READ_MEDIA_IMAGES,
            MediaPermissions.READ_MEDIA_VIDEO,
            MediaPermissions.ACCESS_MEDIA_LOCATION,
        )
    }

    @Test
    fun `refusing the location permission does not reduce media access`() {
        // Places is a bonus, not a dependency: a phone that says no to location still has a
        // fully working gallery, so this must not collapse into MediaAccess.None.
        val state = MediaPermissionState(
            images = true,
            video = true,
            userSelected = false,
            mediaLocation = false,
        )
        assertThat(state.access).isEqualTo(MediaAccess.Full)
        assertThat(state.canIndex).isTrue()
    }
}
