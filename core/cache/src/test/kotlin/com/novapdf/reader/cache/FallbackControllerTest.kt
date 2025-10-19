package com.novapdf.reader.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackControllerTest {

    @Test
    fun `activations are idempotent`() {
        var activatedCount = 0
        val controller = FallbackController(onActivated = { activatedCount++ })

        controller.activate("oom")
        controller.activate("oom")

        assertEquals(1, activatedCount)
        assertTrue(controller.isActive)
        assertEquals(setOf("oom"), controller.reasons())
    }

    @Test
    fun `deactivation only fires when last reason cleared`() {
        var deactivatedCount = 0
        val controller = FallbackController(
            onActivated = {},
            onDeactivated = { deactivatedCount++ },
        )

        controller.activate("network")
        controller.activate("disk")
        controller.deactivate("network")

        assertEquals(0, deactivatedCount)
        assertTrue(controller.isActive)

        controller.deactivate("disk")

        assertEquals(1, deactivatedCount)
        assertFalse(controller.isActive)
    }

    @Test
    fun `clear removes all reasons`() {
        var deactivated = false
        val controller = FallbackController(
            onActivated = {},
            onDeactivated = { deactivated = true },
        )

        controller.activate("oom")
        controller.activate("disk")
        controller.clear()

        assertTrue(deactivated)
        assertFalse(controller.isActive)
        assertTrue(controller.reasons().isEmpty())
    }
}
