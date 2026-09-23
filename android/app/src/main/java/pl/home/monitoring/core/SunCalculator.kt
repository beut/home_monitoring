package pl.home.monitoring.core

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

data class SunTimes(val sunrise: ZonedDateTime, val sunset: ZonedDateTime)

/** Sunrise/sunset from the NOAA general solar position algorithm (zenith 90.833°). */
object SunCalculator {

    fun sunTimes(date: LocalDate, lat: Double, lon: Double, zone: ZoneId): SunTimes {
        // First pass at solar noon, then refine each event at its own approximate time.
        val noon = solarNoonMinutes(date, lon, 720.0)
        val rise0 = eventMinutes(date, lat, lon, noon, rising = true)
        val set0 = eventMinutes(date, lat, lon, noon, rising = false)
        val rise = eventMinutes(date, lat, lon, rise0, rising = true)
        val set = eventMinutes(date, lat, lon, set0, rising = false)
        val midnightUtc = date.atStartOfDay(ZoneOffset.UTC)
        return SunTimes(
            sunrise = midnightUtc.plusSeconds((rise * 60).toLong()).withZoneSameInstant(zone),
            sunset = midnightUtc.plusSeconds((set * 60).toLong()).withZoneSameInstant(zone),
        )
    }

    fun daylightHours(date: LocalDate, lat: Double, lon: Double, zone: ZoneId): Double {
        val t = sunTimes(date, lat, lon, zone)
        return java.time.Duration.between(t.sunrise, t.sunset).seconds / 3600.0
    }

    private fun solarNoonMinutes(date: LocalDate, lon: Double, utcMinutes: Double): Double {
        val s = solar(date, utcMinutes)
        return 720 - 4 * lon - s.eqTimeMinutes
    }

    private fun eventMinutes(date: LocalDate, lat: Double, lon: Double, utcMinutes: Double, rising: Boolean): Double {
        val s = solar(date, utcMinutes)
        val latR = Math.toRadians(lat)
        val declR = Math.toRadians(s.declinationDeg)
        val cosHa = (cos(Math.toRadians(90.833)) / (cos(latR) * cos(declR)) - tan(latR) * tan(declR)).coerceIn(-1.0, 1.0)
        val haDeg = Math.toDegrees(acos(cosHa))
        val noon = 720 - 4 * lon - s.eqTimeMinutes
        return if (rising) noon - 4 * haDeg else noon + 4 * haDeg
    }

    private data class Solar(val declinationDeg: Double, val eqTimeMinutes: Double)

    private fun solar(date: LocalDate, utcMinutes: Double): Solar {
        val jd = date.toEpochDay() + 2440587.5 + utcMinutes / 1440.0
        val jc = (jd - 2451545.0) / 36525.0
        val l0 = (280.46646 + jc * (36000.76983 + jc * 0.0003032)).mod(360.0)
        val m = 357.52911 + jc * (35999.05029 - 0.0001537 * jc)
        val e = 0.016708634 - jc * (0.000042037 + 0.0000001267 * jc)
        val mR = Math.toRadians(m)
        val c = sin(mR) * (1.914602 - jc * (0.004817 + 0.000014 * jc)) +
            sin(2 * mR) * (0.019993 - 0.000101 * jc) + sin(3 * mR) * 0.000289
        val trueLong = l0 + c
        val omega = Math.toRadians(125.04 - 1934.136 * jc)
        val appLong = trueLong - 0.00569 - 0.00478 * sin(omega)
        val meanObliq = 23 + (26 + (21.448 - jc * (46.815 + jc * (0.00059 - jc * 0.001813))) / 60) / 60
        val obliqCorr = meanObliq + 0.00256 * cos(omega)
        val decl = Math.toDegrees(asin(sin(Math.toRadians(obliqCorr)) * sin(Math.toRadians(appLong))))
        val y = tan(Math.toRadians(obliqCorr / 2)).let { it * it }
        val l0R = Math.toRadians(l0)
        val eqTime = 4 * Math.toDegrees(
            y * sin(2 * l0R) - 2 * e * sin(mR) + 4 * e * y * sin(mR) * cos(2 * l0R) -
                0.5 * y * y * sin(4 * l0R) - 1.25 * e * e * sin(2 * mR),
        )
        return Solar(decl, eqTime)
    }
}
