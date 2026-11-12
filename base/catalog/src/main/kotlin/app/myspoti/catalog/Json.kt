/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

private val parser = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun parseObject(provider: String, body: String): JsonObject =
    runCatching { parser.parseToJsonElement(body) as JsonObject }
        .getOrElse { throw ProviderDown(provider, "unreadable answer", it) }

internal fun parseArray(provider: String, body: String): JsonArray =
    runCatching { parser.parseToJsonElement(body) as JsonArray }
        .getOrElse { throw ProviderDown(provider, "unreadable answer", it) }

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray).objects()
internal fun JsonArray?.objects(): List<JsonObject> = this?.filterIsInstance<JsonObject>().orEmpty()
internal fun JsonElement?.strings(): List<String> = (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
