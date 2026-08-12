package com.galleryorganizer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Proves the JVM unit-test source set is wired up and runs without a device. */
class ScaffoldSmokeTest {

    @Test
    fun `truth assertions are available`() {
        assertThat(listOf(1, 2, 3)).containsExactly(1, 2, 3).inOrder()
    }
}
