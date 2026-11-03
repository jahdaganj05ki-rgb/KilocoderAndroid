package com.kilocoder.android

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildConfigTest {
    @Test
    fun applicationIdIsStable() {
        assertEquals("com.kilocoder.android", BuildConfig.APPLICATION_ID)
    }
}
