package pl.home.monitoring.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class FormattersTest {
    @Test fun powerBelowKw() = assertEquals("850 W", Formatters.formatPower(850))
    @Test fun powerZero() = assertEquals("0 W", Formatters.formatPower(0))
    @Test fun powerKw() = assertEquals("1,2 kW", Formatters.formatPower(1234))
    @Test fun powerNull() = assertEquals("—", Formatters.formatPower(null))
    @Test fun kwh() = assertEquals("12,4 kWh", Formatters.formatKwh(12.44))
    @Test fun kwhLarge() = assertEquals("28 320,8 kWh", Formatters.formatKwh(28320.78).replace(' ', ' ').replace(' ', ' '))
    @Test fun kwhNull() = assertEquals("—", Formatters.formatKwh(null))
    @Test fun time() = assertEquals("14:05", Formatters.formatTime(LocalTime.of(14, 5)))
}
