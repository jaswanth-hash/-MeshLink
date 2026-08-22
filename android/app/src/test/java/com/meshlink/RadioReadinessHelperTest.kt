package com.meshlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioReadinessHelperTest {

    @Test
    fun isLocationRequired_returnsTrueForApiBelow33() {
        assertTrue(RadioReadinessHelper.isLocationRequired(24)) // Android 7.0
        assertTrue(RadioReadinessHelper.isLocationRequired(30)) // Android 11
        assertTrue(RadioReadinessHelper.isLocationRequired(31)) // Android 12
        assertTrue(RadioReadinessHelper.isLocationRequired(32)) // Android 12L
    }

    @Test
    fun isLocationRequired_returnsFalseForApi33AndAbove() {
        assertFalse(RadioReadinessHelper.isLocationRequired(33)) // Android 13
        assertFalse(RadioReadinessHelper.isLocationRequired(34)) // Android 14
        assertFalse(RadioReadinessHelper.isLocationRequired(35)) // Android 15
    }
}
