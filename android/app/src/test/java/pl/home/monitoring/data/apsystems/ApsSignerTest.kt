package pl.home.monitoring.data.apsystems

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApsSignerTest {

    @Test
    fun `signature matches the reference python implementation`() {
        val sig = ApsSigner.sign(
            appId = "testAppId",
            appSecret = "testSecret",
            timestamp = "1700000000000",
            nonce = "0123456789abcdef0123456789abcdef",
            path = "/user/api/v2/systems/summary",
            method = "GET",
        )
        assertEquals("/+AeRWeNIkLQanWAJAq3DiMz9H7s7uHYU+PeiBmNP9g=", sig)
    }

    @Test
    fun `last path segment is the final segment`() {
        assertEquals("ABC", ApsSigner.lastPathSegment("/user/api/v2/systems/{sid}/devices/ecu/energy/ABC"))
        assertEquals("summary", ApsSigner.lastPathSegment("/user/api/v2/systems/summary/"))
    }

    @Test
    fun `query string is not part of the segment`() {
        assertEquals(
            "ABC",
            ApsSigner.lastPathSegment("/systems/x/devices/ecu/energy/ABC?energy_level=minutely&date_range=2026-09-23"),
        )
    }

    @Test
    fun `nonce is 32 lowercase hex chars`() {
        assertTrue(Regex("^[0-9a-f]{32}$").matches(ApsSigner.newNonce()))
    }
}
