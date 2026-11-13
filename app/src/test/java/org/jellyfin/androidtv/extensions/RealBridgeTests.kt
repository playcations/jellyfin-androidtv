package org.jellyfin.androidtv.extensions

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** Optional real, disposable Jellyfin checks. Secrets are read privately and never logged/serialized. */
class RealBridgeTests : FunSpec({
	val privatePath = System.getenv("FAMILYFIN_NATIVE_REAL_SESSION")
	if (privatePath != null) {
		val config = Json.parseToJsonElement(File(privatePath).readText()).jsonObject
		val server = config.getValue("server").jsonPrimitive.content
		val token = config.getValue("token").jsonPrimitive.content
		fun get(path: String): JsonObject {
			val c = URI(server + path).toURL().openConnection() as HttpURLConnection
			try {
				c.instanceFollowRedirects = false
				c.setRequestProperty("Authorization", extensionAuthorization(token))
				check(c.responseCode == 200) { "Disposable fixture API request failed" }
				return Json.parseToJsonElement(c.inputStream.bufferedReader().use { it.readText() }).jsonObject
			} finally { c.disconnect() }
		}
		val user = get("/Users/Me").getValue("Id").jsonPrimitive.content
		val session = ExtensionSession(server, user, token)
		val repo = ExtensionRepository({ session })
		test("real bridge authenticates current viewer and decodes all three providers with native client") {
			val providers = repo.discover()
			providers.size shouldBe 3
			for (provider in providers) for (entry in provider.entryPoints) {
				repo.screen(provider.id, entry.screenId).items.isNotEmpty() shouldBe true
			}
		}
		test("real bridge recommendation home rows use standard media and strict page envelopes") {
			val rows = repo.discover().flatMap { provider -> provider.homeRows.map { provider.id to it } }
			rows.isNotEmpty() shouldBe true
			for ((provider, row) in rows) {
				val page = repo.screen(provider, row.screenId)
				page.items.all { it.media != null } shouldBe true
			}
		}
		test("real native guide scope supplies only exact authorized channel and program requests") {
			var checked = 0
			for (provider in repo.discover()) {
				val queue = ArrayDeque(provider.entryPoints.map { it.screenId })
				val seen = mutableSetOf<String>()
				while (queue.isNotEmpty()) {
					val id = queue.removeFirst()
					if (!seen.add(id) || seen.size > 30) continue
					for (entry in repo.screen(provider.id, id).items) when (val action = entry.action) {
						is ExtensionAction.OpenScreen -> queue.add(action.screenId)
						is ExtensionAction.OpenGuide -> {
							val scope = repo.guide(action.extensionId, action.scopeId)
							for (ch in scope.channelIds) {
								val returned = get("/LiveTv/Channels/$ch").getValue("Id").jsonPrimitive.content
								returned.toUUIDOrNull() shouldBe ch
							}
							val ids = URLEncoder.encode(scope.channelIds.joinToString(","), "UTF-8")
							val programs = get("/LiveTv/Programs?channelIds=$ids").getValue("Items").jsonArray
							for (program in programs) {
								val channel = program.jsonObject.getValue("ChannelId").jsonPrimitive.content
								(scope.channelIds.contains(channel.toUUIDOrNull())) shouldBe true
							}
							checked++
						}
						else -> Unit
					}
				}
			}
			(checked >= 2) shouldBe true
		}
	}
})
