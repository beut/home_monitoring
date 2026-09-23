package pl.home.monitoring.data.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import pl.home.monitoring.domain.ApsSystemInfo
import java.time.ZoneId

val DEFAULT_ZONE: ZoneId = ZoneId.of("Europe/Warsaw")

fun zoneOrDefault(id: String?): ZoneId = id?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: DEFAULT_ZONE

class SystemInfoStore(private val store: DataStore<Preferences>) {
    val info: Flow<ApsSystemInfo?> = store.data.map { p ->
        p[ECU]?.let { ApsSystemInfo(it, zoneOrDefault(p[ZONE]), p[CAPACITY]) }
    }

    suspend fun current(): ApsSystemInfo? = info.first()

    suspend fun save(info: ApsSystemInfo) {
        store.edit {
            it[ECU] = info.ecuId
            it[ZONE] = info.timezone.id
            if (info.capacityKwp != null) it[CAPACITY] = info.capacityKwp else it.remove(CAPACITY)
        }
    }

    suspend fun clear() {
        store.edit {
            it.remove(ECU)
            it.remove(ZONE)
            it.remove(CAPACITY)
        }
    }

    private companion object {
        val ECU = stringPreferencesKey("ecu_id")
        val ZONE = stringPreferencesKey("timezone")
        val CAPACITY = doublePreferencesKey("capacity_kwp")
    }
}

data class Location(val latitude: Double = DEFAULT_LAT, val longitude: Double = DEFAULT_LON) {
    companion object {
        const val DEFAULT_LAT = 52.0
        const val DEFAULT_LON = 19.0
        fun isValid(lat: Double, lon: Double) = lat in -90.0..90.0 && lon in -180.0..180.0
    }
}

class LocationStore(private val store: DataStore<Preferences>) {
    val location: Flow<Location> = store.data.map {
        Location(it[LAT] ?: Location.DEFAULT_LAT, it[LON] ?: Location.DEFAULT_LON)
    }

    suspend fun current(): Location = location.first()

    /** Rejects out-of-range values; returns false when nothing was saved. */
    suspend fun save(lat: Double, lon: Double): Boolean {
        if (!Location.isValid(lat, lon)) return false
        store.edit {
            it[LAT] = lat
            it[LON] = lon
        }
        return true
    }

    private companion object {
        val LAT = doublePreferencesKey("latitude")
        val LON = doublePreferencesKey("longitude")
    }
}
