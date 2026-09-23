package pl.home.monitoring.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class SunCalculatorTest {

    private val zone = ZoneId.of("Europe/Warsaw")

    private fun check(date: String, rise: String, set: String) {
        val t = SunCalculator.sunTimes(LocalDate.parse(date), 52.0, 19.0, zone)
        assertNear(date, LocalTime.parse(rise), t.sunrise.toLocalTime())
        assertNear(date, LocalTime.parse(set), t.sunset.toLocalTime())
    }

    private fun assertNear(label: String, expected: LocalTime, actual: LocalTime) {
        val diff = Duration.between(expected, actual).abs()
        assertTrue("$label expected $expected got $actual", diff <= Duration.ofMinutes(5))
    }

    @Test fun summerSolstice() = check("2026-06-21", "04:23", "21:07")
    @Test fun equinox() = check("2026-09-23", "06:30", "18:41")
    @Test fun winterSolstice() = check("2026-12-21", "07:49", "15:34")

    @Test
    fun daylightHours() {
        val june = SunCalculator.daylightHours(LocalDate.parse("2026-06-21"), 52.0, 19.0, zone)
        val dec = SunCalculator.daylightHours(LocalDate.parse("2026-12-21"), 52.0, 19.0, zone)
        assertTrue("june $june", june in 16.5..16.9)
        assertTrue("dec $dec", dec in 7.5..7.9)
    }
}
