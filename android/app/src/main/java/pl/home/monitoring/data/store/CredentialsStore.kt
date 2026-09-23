package pl.home.monitoring.data.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import pl.home.monitoring.data.security.StringCipher
import pl.home.monitoring.domain.ApsCredentials

/** APsystems credentials, encrypted with a Keystore key (FR-010). */
class CredentialsStore(
    private val store: DataStore<Preferences>,
    private val cipher: StringCipher,
    private val devPrefill: ApsCredentials? = null,
) {
    val credentials: Flow<ApsCredentials?> = store.data.map { p ->
        val a = p[APP_ID]
        val s = p[SECRET]
        val i = p[SID]
        if (a == null || s == null || i == null) {
            null
        } else {
            try {
                ApsCredentials.orNull(cipher.decrypt(a), cipher.decrypt(s), cipher.decrypt(i))
            } catch (e: Exception) {
                // Key lost (e.g. restored to another device): the stored values are useless.
                null
            }
        }
    }.distinctUntilChanged()

    suspend fun current(): ApsCredentials? = credentials.first()

    suspend fun save(c: ApsCredentials) {
        val a = cipher.encrypt(c.appId)
        val s = cipher.encrypt(c.appSecret)
        val i = cipher.encrypt(c.sid)
        store.edit {
            it[APP_ID] = a
            it[SECRET] = s
            it[SID] = i
        }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }

    /** Debug builds only: values from local.properties to pre-fill the Konta form. Never saved automatically. */
    fun devPrefill(): ApsCredentials? = devPrefill

    private companion object {
        val APP_ID = stringPreferencesKey("aps_app_id")
        val SECRET = stringPreferencesKey("aps_app_secret")
        val SID = stringPreferencesKey("aps_sid")
    }
}
