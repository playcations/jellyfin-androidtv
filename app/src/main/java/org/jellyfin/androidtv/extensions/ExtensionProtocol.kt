package org.jellyfin.androidtv.extensions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.util.UUID

/** The v1 envelope is camelCase; media uses the unmodified Jellyfin SDK serializer. */
data class ExtensionLink(val id: String, val title: String, val screenId: String)
data class ClientExtension(
	val id: String,
	val title: String,
	val version: String,
	val entryPoints: List<ExtensionLink>,
	val homeRows: List<ExtensionLink>,
)
data class ExtensionEntry(
	val id: String,
	val title: String,
	val media: BaseItemDto?,
	val action: ExtensionAction,
	val extensionId: String? = null,
)
data class ExtensionPage(
	val id: String,
	val title: String,
	val view: String,
	val startIndex: Int,
	val total: Int,
	val items: List<ExtensionEntry>,
) {
	val nextIndex get() = (startIndex + items.size).takeIf { it < total }
}
sealed interface ExtensionAction {
	data class OpenScreen(val extensionId: String, val screenId: String) : ExtensionAction
	data class OpenItem(val itemId: UUID) : ExtensionAction
	data class PlayItem(val itemId: UUID) : ExtensionAction
	data class OpenGuide(val extensionId: String, val scopeId: String) : ExtensionAction
}
data class ExtensionGuideScope(val id: String, val title: String, val channelIds: Set<UUID>)

private const val MAX_IDENTIFIER_LENGTH = 200
private const val MAX_LABEL_LENGTH = 1000
private const val MAX_LINKS = 100
private const val MAX_PROVIDERS = 200
private const val MAX_RECORDS = 1_000_000
private const val MAX_GUIDE_CHANNELS = 10_000

private fun JsonObject.text(key: String): String = (getValue(key) as JsonPrimitive).also {
	require(it.isString)
}.content
private fun JsonObject.label(key: String): String = text(key).also {
	require(it.isNotBlank() && it.length <= MAX_LABEL_LENGTH)
}
private fun JsonObject.id(key: String) = ExtensionProtocol.identifier(text(key))
private fun JsonObject.integer(key: String) = (getValue(key) as JsonPrimitive).also { require(!it.isString) }.int
private fun JsonObject.array(key: String, max: Int): List<JsonObject> = getValue(key).jsonArray
	.also { require(it.size <= max) }
	.map { it.jsonObject }
private fun uuid(value: String) = requireNotNull(value.toUUIDOrNull()).also { require(it != UUID(0, 0)) }
private fun links(o: JsonObject, name: String): List<ExtensionLink> = o.array(name, MAX_LINKS)
	.map { ExtensionLink(it.id("id"), it.label("title"), it.id("screenId")) }
	.also { require(it.map { link -> link.id }.distinct().size == it.size) }

object ExtensionProtocol {
	const val PAGE_SIZE = 40
	const val MAX_PAGE_SIZE = 100
	private val json = Json { ignoreUnknownKeys = true }
	fun identifier(value: String): String = value.also {
		require(it.length in 1..MAX_IDENTIFIER_LENGTH && it.matches(Regex("[A-Za-z0-9_:.\\-]+")) && it != "." && it != "..") {
			"Invalid extension identifier"
		}
	}
	private fun envelope(body: String): JsonObject = json.parseToJsonElement(body).jsonObject.also {
		require(it.integer("protocolVersion") == 1) { "Unsupported extension protocol" }
	}
	fun discovery(body: String): List<ClientExtension> = envelope(body).getValue("extensions").jsonArray
		.also { require(it.size <= MAX_PROVIDERS) }
		.mapNotNull { raw ->
			// One broken provider, including a non-object entry, must not remove working providers.
			runCatching {
				val o = raw.jsonObject
				ClientExtension(o.id("id"), o.label("title"), o.label("version"), links(o, "entryPoints"), links(o, "homeRows"))
			}.getOrNull()
		}.groupBy { it.id }.values.filter { it.size == 1 }.map { it.single() }

	fun action(o: JsonObject, provider: String): ExtensionAction = when (o.text("type")) {
		"openScreen" -> ExtensionAction.OpenScreen(o.id("extensionId").also { require(it == provider) }, o.id("screenId"))
		"openItem" -> ExtensionAction.OpenItem(uuid(o.text("itemId")))
		"playItem" -> ExtensionAction.PlayItem(uuid(o.text("itemId")))
		"openGuide" -> ExtensionAction.OpenGuide(o.id("extensionId").also { require(it == provider) }, o.id("scopeId"))
		else -> error("Unsupported extension action")
	}
	fun page(body: String, provider: String, screen: String, start: Int, limit: Int): ExtensionPage {
		require(start >= 0 && limit in 1..MAX_PAGE_SIZE)
		val o = envelope(body)
		require(o.id("id") == screen && o.integer("startIndex") == start)
		val total = o.integer("totalRecordCount").also { require(it in start..MAX_RECORDS) }
		val view = o.text("view").also { require(it in listOf("grid", "categories")) }
		val items = o.array("items", limit).map { item ->
			val media = item["media"]?.takeUnless { it is JsonNull }?.let { json.decodeFromJsonElement<BaseItemDto>(it) }
			ExtensionEntry(
				item.id("id"),
				item.label("title"),
				media,
				action(item.getValue("action").jsonObject, provider),
				provider
			)
		}
		require(start.toLong() + items.size <= total && (items.isNotEmpty() || start == total))
		require(items.map { it.id }.distinct().size == items.size)
		return ExtensionPage(screen, o.label("title"), view, start, total, items)
	}
	fun scope(body: String, id: String): ExtensionGuideScope {
		val o = envelope(body)
		require(o.id("id") == id)
		val ids = o.getValue("channelIds").jsonArray
			.also { require(it.size in 1..MAX_GUIDE_CHANNELS) }
			.map { uuid(it.jsonPrimitive.content) }
			.toSet()
		return ExtensionGuideScope(id, o.label("title"), ids)
	}
}
