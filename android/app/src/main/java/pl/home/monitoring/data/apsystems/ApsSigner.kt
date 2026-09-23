package pl.home.monitoring.data.apsystems

import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Request signing per APsystems OpenAPI §2.2 — a port of `_sign()` from the reference Python script. */
object ApsSigner {
    const val METHOD = "HmacSHA256"

    fun sign(appId: String, appSecret: String, timestamp: String, nonce: String, path: String, method: String): String {
        val stringToSign = "$timestamp/$nonce/$appId/${lastPathSegment(path)}/$method/$METHOD"
        val mac = Mac.getInstance(METHOD)
        mac.init(SecretKeySpec(appSecret.toByteArray(Charsets.UTF_8), METHOD))
        return Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8)))
    }

    fun lastPathSegment(path: String): String =
        path.substringBefore('?').trimEnd('/').substringAfterLast('/')

    fun newNonce(): String = UUID.randomUUID().toString().replace("-", "").lowercase()
}
