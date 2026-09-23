package pl.home.monitoring.data.apsystems

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import pl.home.monitoring.domain.ApsCredentials
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data object NoData : ApiResult<Nothing>
    data class AuthError(val code: Int) : ApiResult<Nothing>
    data class Throttled(val code: Int) : ApiResult<Nothing>
    data class ServiceError(val code: Int?) : ApiResult<Nothing>
    data object Offline : ApiResult<Nothing>
}

/** Counts every HTTP attempt against the monthly quota. */
fun interface CallRecorder {
    suspend fun recordCall(now: Instant)
}

interface ApsApi {
    suspend fun details(creds: ApsCredentials): ApiResult<DetailsData>
    suspend fun summary(creds: ApsCredentials): ApiResult<SummaryData>
    suspend fun minutely(creds: ApsCredentials, ecuId: String, date: LocalDate): ApiResult<MinutelyData>
}

class ApsApiClient(
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    private val http: OkHttpClient,
    private val recorder: CallRecorder,
    private val clock: Clock,
) : ApsApi {

    override suspend fun details(creds: ApsCredentials) =
        get("/user/api/v2/systems/details/${creds.sid}", emptyMap(), creds, DetailsData.serializer())

    override suspend fun summary(creds: ApsCredentials) =
        get("/user/api/v2/systems/summary/${creds.sid}", emptyMap(), creds, SummaryData.serializer())

    override suspend fun minutely(creds: ApsCredentials, ecuId: String, date: LocalDate) =
        get(
            "/user/api/v2/systems/${creds.sid}/devices/ecu/energy/$ecuId",
            linkedMapOf("energy_level" to "minutely", "date_range" to date.toString()),
            creds,
            MinutelyData.serializer(),
        )

    private suspend fun <T> get(
        path: String,
        query: Map<String, String>,
        creds: ApsCredentials,
        serializer: KSerializer<T>,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        val now = clock.instant()
        recorder.recordCall(now)

        val url = baseUrl.newBuilder().encodedPath(path).apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val ts = now.toEpochMilli().toString()
        val nonce = ApsSigner.newNonce()
        val request = Request.Builder()
            .url(url)
            .get()
            .header("X-CA-AppId", creds.appId)
            .header("X-CA-Timestamp", ts)
            .header("X-CA-Nonce", nonce)
            .header("X-CA-Signature-Method", ApsSigner.METHOD)
            .header("X-CA-Signature", ApsSigner.sign(creds.appId, creds.appSecret, ts, nonce, path, "GET"))
            .build()

        val body = try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext ApiResult.ServiceError(null)
                resp.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            return@withContext ApiResult.Offline
        }

        val envelope = try {
            ApsJson.decodeFromString(ApsResponse.serializer(serializer), body)
        } catch (e: Exception) {
            return@withContext ApiResult.ServiceError(null)
        }
        mapCode(envelope)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.apsystemsema.com:9282/"

        /** Code mapping per contracts/apsystems-openapi.md. */
        fun <T> mapCode(r: ApsResponse<T>): ApiResult<T> = when (val code = r.code) {
            0 -> r.data?.let { ApiResult.Success(it) } ?: ApiResult.NoData
            1001 -> ApiResult.NoData
            2005, 7001, 7002, 7003 -> ApiResult.Throttled(code)
            in 2000..2999, in 4000..4999 -> ApiResult.AuthError(code)
            else -> ApiResult.ServiceError(code)
        }
    }
}
