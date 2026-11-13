package org.jellyfin.androidtv.extensions

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class ServerSnapshotTests : FunSpec({
	val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
		it.parentFile
	}.first { File(it, "dev/extension_fixture.py").exists() }
	val fixtures = File(root, "dev/server-snapshots")
	fun read(name: String) = File(fixtures, "$name.json").readText()
	test("exact real-server discovery includes three providers without client branches") {
		ExtensionProtocol.discovery(read("discovery")).size shouldBe 3
	}
	test("all captured real-server screens use the same strict parser including normal SDK media") {
		for ((filename, provider) in listOf(
			"foryou-screen" to "for-you",
			"livetv-root" to "live-tv",
			"livetv-nested" to "live-tv",
			"livetv-child" to "live-tv",
			"third-provider" to "library-sampler"
		)) {
			val body = read(filename)
			val o = Json.parseToJsonElement(body).jsonObject
			val screen = o.getValue("id").jsonPrimitive.content
			val start = o.getValue("startIndex").jsonPrimitive.int
			ExtensionProtocol.page(body, provider, screen, start, 100).items.isNotEmpty() shouldBe true
		}
	}
	test("captured parent and child native guide scopes decode exactly without UUID loss") {
		for (name in listOf("guide-scope", "guide-scope-child")) {
			val body = read(name)
			val o = Json.parseToJsonElement(body).jsonObject
			val id = o.getValue("id").jsonPrimitive.content
			ExtensionProtocol.scope(body, id).channelIds.size shouldBe o.getValue("channelIds").jsonArray.size
		}
	}
})
