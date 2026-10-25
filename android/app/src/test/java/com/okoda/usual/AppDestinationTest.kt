package com.okoda.usual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppDestinationTest {
    @Test fun acceptsOnlyOpaqueAuthorizedRouteShapes() {
        val route = AppDestination("content", "couple-1", "item_2")
        assertEquals(route, AppDestination.parse(route.link()))
        assertEquals(AppDestination("draw"), AppDestination.parse("usual://action/draw"))
        listOf(null, "https://example.test", "usual://content/other/id?token=secret", "usual://content/a/id#x", "usual://content/a/%2Fprivate", "usual://user@content/a/b", "usual://content:44/a/b", "usual://action/delete", "usual://game/a/../b", "usual://content/a/" + "x".repeat(129)).forEach { assertNull(it, AppDestination.parse(it)) }
    }
}
