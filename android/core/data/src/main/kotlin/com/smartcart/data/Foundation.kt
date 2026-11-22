package com.smartcart.data

import com.smartcart.model.ApiError
import com.smartcart.model.ErrorCode
import com.smartcart.model.ErrorEnvelope
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.serializer
import java.io.File

@OptIn(ExperimentalSerializationApi::class)
object ApiJson {
    /** Client decoding: tolerant of new server fields (forward compatibility). */
    val lenient: Json = Json {
        namingStrategy = JsonNamingStrategy.SnakeCase
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** Contract tests: any field the client model does not know about fails the build. */
    val strict: Json = Json {
        namingStrategy = JsonNamingStrategy.SnakeCase
        ignoreUnknownKeys = false
        explicitNulls = false
    }
}

/** Result of a backend call. Phase 1 fakes and the Phase 3 HTTP client both return this. */
sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val error: ApiError) : ApiResult<Nothing>

    fun valueOrNull(): T? = (this as? Success)?.value
}

inline fun <T, R> ApiResult<T>.map(f: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(f(value))
    is ApiResult.Failure -> this
}

fun clientError(code: ErrorCode, message: String) =
    ApiResult.Failure(ApiError(code = code, message = message, correlationId = "client"))

/** Reads contract fixtures by relative path, e.g. "comparison/grocery.complete.json". */
fun interface FixtureSource {
    fun read(path: String): String
}

class FileFixtureSource(private val root: File) : FixtureSource {
    override fun read(path: String): String = File(root, path).readText()
}

class Fixtures(private val source: FixtureSource, private val json: Json = ApiJson.lenient) {
    fun <T> load(path: String, serializer: KSerializer<T>): T = json.decodeFromString(serializer, source.read(path))
    inline fun <reified T> load(path: String): T = load(path, serializer())
    fun error(path: String): ApiResult.Failure = ApiResult.Failure(load(path, ErrorEnvelope.serializer()).error)
}
