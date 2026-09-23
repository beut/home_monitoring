package pl.home.monitoring.data.apsystems

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class ApsDtosTest {

    @Test
    fun `details response`() {
        val r = ApsJson.decodeFromString<ApsResponse<DetailsData>>(
            """{"code":0,"data":{"sid":"X","type":1,"capacity":"9.96","timezone":"Europe/Warsaw",
               "ecu":["216200000001"],"create_date":"2020-07-31","light":1,"authorization_code":"abc"}}""",
        )
        assertEquals(0, r.code)
        assertEquals("216200000001", r.data!!.ecu.first())
        assertEquals("Europe/Warsaw", r.data!!.timezone)
        assertEquals(9.96, r.data!!.capacity.kwhOrNull()!!, 1e-9)
    }

    @Test
    fun `summary with null today`() {
        val r = ApsJson.decodeFromString<ApsResponse<SummaryData>>(
            """{"code":0,"data":{"month":"617.66","year":"6166.11","today":null,"lifetime":"28320.78"}}""",
        )
        val totals = r.data!!.toTotals()
        assertNull(totals.todayKwh)
        assertEquals(617.66, totals.monthKwh!!, 1e-9)
        assertEquals(6166.11, totals.yearKwh!!, 1e-9)
        assertEquals(28320.78, totals.lifetimeKwh!!, 1e-9)
    }

    @Test
    fun `minutely response gives last sample and today`() {
        val r = ApsJson.decodeFromString<ApsResponse<MinutelyData>>(
            """{"code":0,"data":{"today":"20.17","time":["06:05","06:15","18:45"],
               "power":[0,12,24],"energy":["0.00","0.00","0.00"]}}""",
        )
        val d = r.data!!
        assertEquals(20.17, d.today.kwhOrNull()!!, 1e-9)
        assertEquals(LocalTime.of(18, 45) to 24, d.lastSample())
    }

    @Test
    fun `no data envelope with and without data`() {
        val a = ApsJson.decodeFromString<ApsResponse<MinutelyData>>("""{"code":1001,"data":{}}""")
        assertEquals(1001, a.code)
        assertNull(a.data!!.lastSample())
        val b = ApsJson.decodeFromString<ApsResponse<MinutelyData>>("""{"code":1001}""")
        assertEquals(1001, b.code)
        assertNull(b.data)
    }

    @Test
    fun `unknown fields are ignored`() {
        val r = ApsJson.decodeFromString<ApsResponse<SummaryData>>(
            """{"code":0,"extra":1,"data":{"month":"1","foo":{"bar":[1,2]}}}""",
        )
        assertEquals(1.0, r.data!!.month.kwhOrNull()!!, 1e-9)
    }

    @Test
    fun `unparseable kwh becomes null`() {
        assertNull("abc".kwhOrNull())
        assertNull(null.kwhOrNull())
        assertNull("".kwhOrNull())
    }

    @Test
    fun `negative power is clamped and bad times are skipped`() {
        val d = MinutelyData(today = "1", time = listOf("10:00", "10:05"), power = listOf(5, -3))
        assertEquals(LocalTime.of(10, 5) to 0, d.lastSample())
        val bad = MinutelyData(time = listOf("xx"), power = listOf(5))
        assertNull(bad.lastSample())
    }
}
