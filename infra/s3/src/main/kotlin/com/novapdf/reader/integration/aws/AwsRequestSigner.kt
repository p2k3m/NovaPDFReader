package com.novapdf.reader.integration.aws

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.RequestBody
import okio.Buffer

@Singleton
internal class AwsRequestSigner @Inject constructor(
    private val credentialsProvider: AwsCredentialsProvider,
) {

    fun sign(request: Request): Request {
        val credentials = credentialsProvider.credentials()
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val amzDate = now.format(ISO_8601_BASIC_DATE_TIME)
        val dateStamp = now.format(DateTimeFormatter.BASIC_ISO_DATE)

        val payloadHash = hashBody(request.body)
        val canonicalRequest = buildCanonicalRequest(request, payloadHash, amzDate, credentials)
        val credentialScope = "$dateStamp/${credentials.region}/s3/aws4_request"
        val stringToSign = buildStringToSign(amzDate, credentialScope, canonicalRequest)
        val signingKey = signingKey(credentials.secretAccessKey, dateStamp, credentials.region, "s3")
        val signature = hmacSha256Hex(signingKey, stringToSign)
        val signedHeaders = extractSignedHeaders(request, credentials)

        val authorizationHeader = buildString {
            append("AWS4-HMAC-SHA256 ")
            append("Credential=")
            append(credentials.accessKeyId)
            append('/')
            append(credentialScope)
            append(", SignedHeaders=")
            append(signedHeaders)
            append(", Signature=")
            append(signature)
        }

        val builder = request.newBuilder()
            .header("x-amz-date", amzDate)
            .header("x-amz-content-sha256", payloadHash)
            .header("Authorization", authorizationHeader)

        credentials.sessionToken?.let { token ->
            builder.header("x-amz-security-token", token)
        }

        return builder.build()
    }

    private fun buildCanonicalRequest(
        request: Request,
        payloadHash: String,
        amzDate: String,
        credentials: AwsCredentials,
    ): String {
        val url = request.url
        val canonicalUri = url.encodedPath.ifEmpty { "/" }
        val canonicalQuery = canonicalQueryString(url)
        val canonicalHeaders = canonicalHeaders(request, url, amzDate, payloadHash, credentials)
        val signedHeaders = extractSignedHeaders(request, credentials)
        return buildString {
            append(request.method.uppercase(Locale.US))
            append('\n')
            append(canonicalUri)
            append('\n')
            append(canonicalQuery)
            append('\n')
            append(canonicalHeaders)
            append('\n')
            append(signedHeaders)
            append('\n')
            append(payloadHash)
        }
    }

    private fun canonicalHeaders(
        request: Request,
        url: HttpUrl,
        amzDate: String,
        payloadHash: String,
        credentials: AwsCredentials,
    ): String {
        val headerMap = sortedMapOf<String, MutableList<String>>()
        headerMap["host"] = mutableListOf(url.host.lowercase(Locale.US))
        headerMap["x-amz-date"] = mutableListOf(amzDate)
        headerMap["x-amz-content-sha256"] = mutableListOf(payloadHash)
        credentials.sessionToken?.let { token ->
            headerMap["x-amz-security-token"] = mutableListOf(token)
        }

        for ((name, value) in request.headers) {
            val canonicalName = name.lowercase(Locale.US)
            val canonicalValue = value.trim().replace(WHITESPACE_REGEX, " ")
            val values = headerMap.getOrPut(canonicalName) { mutableListOf() }
            values += canonicalValue
        }

        return buildString {
            headerMap.forEach { (name, values) ->
                append(name)
                append(':')
                append(values.joinToString(",") { it.trim() })
                append('\n')
            }
        }.trimEnd('\n')
    }

    private fun extractSignedHeaders(request: Request, credentials: AwsCredentials): String {
        val headerNames = sortedSetOf<String>()
        headerNames += "host"
        headerNames += "x-amz-date"
        headerNames += "x-amz-content-sha256"
        if (!credentials.sessionToken.isNullOrEmpty()) {
            headerNames += "x-amz-security-token"
        }
        for (name in request.headers.names()) {
            headerNames += name.lowercase(Locale.US)
        }
        return headerNames.joinToString(";")
    }

    private fun canonicalQueryString(url: HttpUrl): String {
        if (url.querySize == 0) return ""
        val pairs = mutableListOf<Pair<String, String>>()
        for (index in 0 until url.querySize) {
            val name = url.queryParameterName(index)
            val value = url.queryParameterValue(index) ?: ""
            pairs += canonicalEncode(name) to canonicalEncode(value)
        }
        return pairs.sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
            .joinToString("&") { (name, value) -> "$name=$value" }
    }

    private fun canonicalEncode(value: String): String {
        val builder = StringBuilder()
        value.toByteArray(UTF_8).forEach { byte ->
            val ch = byte.toInt().toChar()
            if (ch.isUnreserved()) {
                builder.append(ch)
            } else {
                builder.append('%')
                builder.append(((byte.toInt() ushr 4) and 0xF).toHexChar())
                builder.append((byte.toInt() and 0xF).toHexChar())
            }
        }
        return builder.toString()
    }

    private fun Char.isUnreserved(): Boolean =
        (this in 'A'..'Z') ||
            (this in 'a'..'z') ||
            (this in '0'..'9') ||
            this == '-' ||
            this == '_' ||
            this == '.' ||
            this == '~'

    private fun Int.toHexChar(): Char =
        "0123456789ABCDEF"[this]

    private fun hashBody(body: RequestBody?): String {
        if (body == null) return EMPTY_BODY_SHA256
        val buffer = Buffer()
        body.writeTo(buffer)
        val bytes = buffer.readByteArray()
        return sha256Hex(bytes)
    }

    private fun buildStringToSign(
        amzDate: String,
        credentialScope: String,
        canonicalRequest: String,
    ): String {
        val canonicalRequestHash = sha256Hex(canonicalRequest.toByteArray(UTF_8))
        return listOf(
            "AWS4-HMAC-SHA256",
            amzDate,
            credentialScope,
            canonicalRequestHash,
        ).joinToString("\n")
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString(separator = "") { byte -> "%02x".format(Locale.US, byte) }
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(UTF_8))
    }

    private fun hmacSha256Hex(key: ByteArray, data: String): String =
        hmacSha256(key, data).joinToString(separator = "") { byte -> "%02x".format(Locale.US, byte) }

    private fun signingKey(secretAccessKey: String, dateStamp: String, region: String, service: String): ByteArray {
        val kSecret = ("AWS4" + secretAccessKey).toByteArray(UTF_8)
        val kDate = hmacSha256(kSecret, dateStamp)
        val kRegion = hmacSha256(kDate, region)
        val kService = hmacSha256(kRegion, service)
        return hmacSha256(kService, "aws4_request")
    }

    companion object {
        private val ISO_8601_BASIC_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        private const val EMPTY_BODY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        private val WHITESPACE_REGEX = "\\s+".toRegex()
    }
}
