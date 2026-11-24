package com.rork.rideax.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One shared HTTP client and JSON configuration for every network call. */
object Http {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    val client: HttpClient = HttpClient(Android) {
        expectSuccess = false
        engine {
            connectTimeout = 15_000
            socketTimeout = 20_000
        }
    }

    fun <T> jsonBody(serializer: KSerializer<T>, value: T): TextContent =
        TextContent(json.encodeToString(serializer, value), ContentType.Application.Json)

    inline fun <reified T> jsonBody(value: T): TextContent =
        TextContent(json.encodeToString(value), ContentType.Application.Json)
}

/** A non-2xx response, carrying the body so callers can map error codes. */
class HttpFailure(val status: Int, val body: String) : Exception("HTTP $status")

suspend fun HttpResponse.textOrThrow(): String {
    val text = bodyAsText()
    if (!status.isSuccess()) throw HttpFailure(status.value, text)
    return text
}

/** ISO-8601 helpers that work back to API 24 without java.time desugaring. */
object Dates {
    private fun formatter(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun isoNow(): String = formatter("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(Date())

    /** Parses Postgres timestamps like `2026-09-01T12:34:56.123456+00:00`. */
    fun parse(value: String?): Long? {
        if (value == null || value.length < 19) return null
        return runCatching { formatter("yyyy-MM-dd'T'HH:mm:ss").parse(value.take(19))?.time }.getOrNull()
    }
}
