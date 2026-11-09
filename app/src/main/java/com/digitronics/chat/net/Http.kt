package com.digitronics.chat.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
    isLenient = false
}

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/** The server answered with an error status. Network failures stay plain IOExceptions. */
class ApiException(val status: Int, val code: String?, override val message: String) : Exception(message) {
    val isTransient: Boolean get() = status == 408 || status == 429 || status >= 500
}

/** Thin JSON-over-HTTP client bound to the app's one origin. */
class ApiClient(private val http: OkHttpClient, baseUrl: String, private val json: Json = ApiJson) {
    private val base: HttpUrl = baseUrl.toHttpUrl()

    fun url(path: String, query: Map<String, String?> = emptyMap()): HttpUrl {
        val builder = base.newBuilder().encodedPath(path)
        query.forEach { (k, v) -> if (v != null) builder.addQueryParameter(k, v) }
        return builder.build()
    }

    suspend fun <T> get(path: String, query: Map<String, String?>, reader: DeserializationStrategy<T>): T =
        execute(Request.Builder().url(url(path, query)).get().build(), reader)

    suspend fun <T> send(method: String, path: String, bodyJson: String?, reader: DeserializationStrategy<T>): T {
        val body = (bodyJson ?: "{}").toRequestBody(JSON_MEDIA)
        return execute(Request.Builder().url(url(path)).method(method, body).build(), reader)
    }

    /**
     * Runs the call SYNCHRONOUSLY on Dispatchers.IO. Deliberate: async
     * `enqueue` shares OkHttp's per-host limit (5) with every other call, and
     * the 401 authenticator blocks its thread while a refresh runs — with five
     * concurrent 401s the refresh itself would queue behind them forever.
     * Synchronous calls are not subject to that limit.
     */
    suspend fun <T> execute(request: Request, reader: DeserializationStrategy<T>): T =
        withContext(Dispatchers.IO) {
            val call = http.newCall(request.newBuilder().header("Accept", "application/json").build())
            val cancelOnJobEnd = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
            val response = try {
                call.execute()
            } finally {
                cancelOnJobEnd?.dispose()
            }
            response.use { res ->
                val text = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw apiError(res.code, text)
                try {
                    json.decodeFromString(reader, text)
                } catch (e: SerializationException) {
                    throw ApiException(res.code, "bad_response", "Unexpected response from the server.")
                } catch (e: IllegalArgumentException) {
                    throw ApiException(res.code, "bad_response", "Unexpected response from the server.")
                }
            }
        }

    private fun apiError(status: Int, text: String): ApiException {
        val parsed = runCatching { json.decodeFromString(ApiErrorBody.serializer(), text) }.getOrNull()
        return ApiException(status, parsed?.error, parsed?.message ?: "Request failed ($status).")
    }
}
