package org.jellyfin.androidtv.extensions

import kotlinx.serialization.json.*
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.util.UUID

/** The v1 envelope is camelCase; media uses the unmodified Jellyfin SDK serializer. */
data class ExtensionLink(val id: String, val title: String, val screenId: String)
data class ClientExtension(val id: String, val title: String, val version: String, val entryPoints: List<ExtensionLink>, val homeRows: List<ExtensionLink>)
data class ExtensionEntry(val id: String, val title: String, val media: BaseItemDto?, val action: ExtensionAction, val extensionId: String? = null)
data class ExtensionPage(val id: String, val title: String, val view: String, val startIndex: Int, val total: Int, val items: List<ExtensionEntry>) {
	val nextIndex get() = (startIndex + items.size).takeIf { it < total }
}
sealed interface ExtensionAction {
	data class OpenScreen(val extensionId: String, val screenId: String) : ExtensionAction
	data class OpenItem(val itemId: UUID) : ExtensionAction
	data class PlayItem(val itemId: UUID) : ExtensionAction
	data class OpenGuide(val extensionId: String, val scopeId: String) : ExtensionAction
}
data class ExtensionGuideScope(val id: String, val title: String, val channelIds: Set<UUID>)

object ExtensionProtocol {
	const val PAGE_SIZE = 40
	private val json = Json { ignoreUnknownKeys = true }
	fun identifier(value: String): String = value.also {
		require(it.length in 1..200 && it.matches(Regex("[A-Za-z0-9_:.\\-]+")) && it != "." && it != "..") { "Invalid extension identifier" }
	}
	private fun JsonObject.text(key: String): String = (getValue(key) as JsonPrimitive).also { require(it.isString) }.content
	private fun JsonObject.label(key: String): String = text(key).also { require(it.isNotBlank() && it.length <= 1000) }
	private fun JsonObject.id(key: String) = identifier(text(key))
	private fun JsonObject.integer(key: String) = (getValue(key) as JsonPrimitive).also { require(!it.isString) }.int
	private fun envelope(body: String): JsonObject = json.parseToJsonElement(body).jsonObject.also { require(it.integer("protocolVersion") == 1) { "Unsupported extension protocol" } }
	private fun JsonObject.array(key: String, max: Int): List<JsonObject> = getValue(key).jsonArray.also { require(it.size <= max) }.map { it.jsonObject }
	private fun uuid(value: String) = requireNotNull(value.toUUIDOrNull()).also { require(it != UUID(0, 0)) }
	private fun links(o: JsonObject, name: String): List<ExtensionLink> = o.array(name, 100).map { ExtensionLink(it.id("id"), it.label("title"), it.id("screenId")) }.also { require(it.map { l -> l.id }.distinct().size == it.size) }
	fun discovery(body: String): List<ClientExtension> = envelope(body).getValue("extensions").jsonArray.also { require(it.size <= 200) }.mapNotNull { raw ->
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
		require(start >= 0 && limit in 1..100)
		val o = envelope(body)
		require(o.id("id") == screen && o.integer("startIndex") == start)
		val total = o.integer("totalRecordCount").also { require(it in start..1_000_000) }
		val view = o.text("view").also { require(it in listOf("grid", "categories")) }
		val items = o.array("items", limit).map { item ->
			val media = item["media"]?.takeUnless { it is JsonNull }?.let { json.decodeFromJsonElement<BaseItemDto>(it) }
			ExtensionEntry(item.id("id"), item.label("title"), media, action(item.getValue("action").jsonObject, provider), provider)
		}
		require(start.toLong() + items.size <= total && (items.isNotEmpty() || start == total))
		require(items.map { it.id }.distinct().size == items.size)
		return ExtensionPage(screen, o.label("title"), view, start, total, items)
	}
	fun scope(body: String, id: String): ExtensionGuideScope {
		val o = envelope(body)
		require(o.id("id") == id)
		val ids = o.getValue("channelIds").jsonArray.also { require(it.size in 1..10000) }.map { uuid(it.jsonPrimitive.content) }.toSet()
		return ExtensionGuideScope(id, o.label("title"), ids)
	}
}
