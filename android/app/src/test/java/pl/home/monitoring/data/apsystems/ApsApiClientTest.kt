package pl.home.monitoring.data.apsystems

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pl.home.monitoring.domain.ApsCredentials
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

class ApsApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ApsApiClient
    private val recorder = CountingRecorder()
    private val creds = ApsCredentials.of("appId", "secret", "SID1")

    class CountingRecorder : CallRecorder {
        var calls = 0
        override suspend fun recordCall(now: Instant) { calls++ }
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val http = OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build()
        client = ApsApiClient(
            baseUrl = server.url("/"),
            http = http,
            recorder = recorder,
            clock = Clock.fixed(Instant.parse("2026-09-23T10:00:00Z"), ZoneOffset.UTC),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    private fun enqueue(body: String, status: Int = 200) =
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))

    @Test
    fun `request carries signed headers and correct path`() = runTest {
        enqueue("""{"code":0,"data":{"today":"1.0","time":["10:00"],"power":[100]}}""")
        client.minutely(creds, "ECU9", LocalDate.of(2026, 9, 23))
        val req = server.takeRequest()
        assertEquals(
            "/user/api/v2/systems/SID1/devices/ecu/energy/ECU9?energy_level=minutely&date_range=2026-09-23",
            req.path,
        )
        assertEquals("appId", req.getHeader("X-CA-AppId"))
        assertEquals("1790157600000", req.getHeader("X-CA-Timestamp"))
        assertEquals("HmacSHA256", req.getHeader("X-CA-Signature-Method"))
        val nonce = req.getHeader("X-CA-Nonce")!!
        assertTrue(Regex("^[0-9a-f]{32}$").matches(nonce))
        val expected = ApsSigner.sign("appId", "secret", "1790157600000", nonce,
            "/user/api/v2/systems/SID1/devices/ecu/energy/ECU9", "GET")
        assertEquals(expected, req.getHeader("X-CA-Signature"))
    }

    @Test
    fun `code mapping`() = runTest {
        val cases = listOf(
            """{"code":0,"data":{"month":"1"}}""" to ApiResult.Success::class,
            """{"code":1001}""" to ApiResult.NoData::class,
            """{"code":4000}""" to ApiResult.AuthError::class,
            """{"code":2001}""" to ApiResult.AuthError::class,
            """{"code":4001}""" to ApiResult.AuthError::class,
            """{"code":2005}""" to ApiResult.Throttled::class,
            """{"code":7002}""" to ApiResult.Throttled::class,
        )
        for ((body, type) in cases) {
            enqueue(body)
            val r = client.summary(creds)
            assertTrue("$body -> $r", type.isInstance(r))
        }
        enqueue("""{"code":5000}""")
        assertEquals(ApiResult.ServiceError(5000), client.summary(creds))
        enqueue("""{"code":9999}""")
        assertEquals(ApiResult.ServiceError(9999), client.summary(creds))
    }

    @Test
    fun `http 500 is service error`() = runTest {
        enqueue("oops", status = 500)
        assertEquals(ApiResult.ServiceError(null), client.summary(creds))
    }

    @Test
    fun `non json body is service error`() = runTest {
        enqueue("<html>")
        assertEquals(ApiResult.ServiceError(null), client.summary(creds))
    }

    @Test
    fun `timeout is offline`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertEquals(ApiResult.Offline, client.summary(creds))
    }

    @Test
    fun `connection failure is offline`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(ApiResult.Offline, client.summary(creds))
    }

    @Test
    fun `every attempt is counted including failures`() = runTest {
        enqueue("""{"code":0,"data":{}}""")
        enqueue("x", status = 500)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        client.summary(creds)
        client.summary(creds)
        client.summary(creds)
        assertEquals(3, recorder.calls)
    }

    @Test
    fun `details parses ecu and timezone`() = runTest {
        enqueue("""{"code":0,"data":{"ecu":["E1"],"timezone":"Europe/Warsaw","capacity":"9.96"}}""")
        val r = client.details(creds) as ApiResult.Success
        assertEquals("E1", r.value.ecu.first())
        assertEquals("/user/api/v2/systems/details/SID1", server.takeRequest().path)
    }
}
