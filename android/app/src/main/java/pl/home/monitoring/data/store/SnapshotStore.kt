package pl.home.monitoring.data.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import pl.home.monitoring.data.apsystems.ApsJson
import pl.home.monitoring.domain.PvSnapshot

/** The last successful PV data (FR-011, SC-002). */
class SnapshotStore(private val store: DataStore<Preferences>) {
    val snapshot: Flow<PvSnapshot?> = store.data.map { p ->
        p[PV]?.let { runCatching { ApsJson.decodeFromString(PvSnapshot.serializer(), it) }.getOrNull() }
    }

    suspend fun current(): PvSnapshot? = snapshot.first()

    suspend fun save(s: PvSnapshot) {
        val json = ApsJson.encodeToString(PvSnapshot.serializer(), s)
        store.edit { it[PV] = json }
    }

    suspend fun clear() {
        store.edit { it.remove(PV) }
    }

    private companion object {
        val PV = stringPreferencesKey("pv_snapshot")
    }
}
