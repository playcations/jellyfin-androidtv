package org.jellyfin.androidtv.extensions

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import java.io.File

class ServerSnapshotTests : FunSpec({
	val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.first { File(it, "dev/extension_fixture.py").exists() }
	val fixtures = File(root, "dev/server-snapshots")
	fun read(name: String) = File(fixtures, "$name.json").readText()
	test("exact real-server discovery includes three providers without client branches") {
		ExtensionProtocol.discovery(read("discovery")).size shouldBe 3
	}
	test("all captured real-server screens use the same strict parser including normal SDK media") {
		for ((filename, provider) in listOf("foryou-screen" to "for-you", "livetv-root" to "live-tv", "livetv-nested" to "live-tv", "livetv-child" to "live-tv", "third-provider" to "library-sampler")) {
			val body = read(filename)
			val o = Json.parseToJsonElement(body).jsonObject
			ExtensionProtocol.page(body, provider, o.getValue("id").jsonPrimitive.content, o.getValue("startIndex").jsonPrimitive.int, 100).items.isNotEmpty() shouldBe true
		}
	}
	test("captured parent and child native guide scopes decode exactly without UUID loss") {
		for (name in listOf("guide-scope", "guide-scope-child")) {
			val body = read(name)
			val o = Json.parseToJsonElement(body).jsonObject
			ExtensionProtocol.scope(body, o.getValue("id").jsonPrimitive.content).channelIds.size shouldBe o.getValue("channelIds").jsonArray.size
		}
	}
})
