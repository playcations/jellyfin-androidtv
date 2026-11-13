package org.jellyfin.androidtv.extensions

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.net.ServerSocket
import java.net.URI

/** End-to-end repository tests against the same local HTTP fixture used by the Android emulator. */
class ExtensionFixtureHttpTests : FunSpec({
	val root = generateSequence(
		File(requireNotNull(System.getProperty("user.dir")))
	) { it.parentFile }.first { File(it, "dev/extension_fixture.py").exists() }
	val port = ServerSocket(0).use { it.localPort }
	val server = "http://127.0.0.1:$port"
	val log = File(root, "app/build/fixture-http").apply { mkdirs() }
	var process: Process? = null
	val session = ExtensionSession(server, "viewer", "fixture-viewer-token")
	val repo = ExtensionRepository({ session })
	fun mode(mode: String) { URI("$server/__fixture/state/$mode").toURL().readText() }
	beforeSpec {
		process = ProcessBuilder(
			"python3", File(root, "dev/extension_fixture.py").path,
			"--port", port.toString(), "--log", File(log, "requests.jsonl").path,
		)
			.redirectErrorStream(true).redirectOutput(File(log, "server.log")).start()
		var ready = false
		repeat(100) {
			if (!ready) {
				ready = runCatching { URI("$server/__fixture/health").toURL().readText().contains("true") }.getOrDefault(false)
				if (!ready) Thread.sleep(50)
			}
		}
		check(ready) { "Fixture server failed to start" }
	}
	afterSpec {
		process?.destroy();
		process?.waitFor(
			5,
			java.util.concurrent.TimeUnit.SECONDS
		);
		if (process?.isAlive == true) process.destroyForcibly()
	}
	beforeTest { mode("normal") }
	test("HTTP three-provider discovery and common media category renderer") {
		val providers = repo.discover()
		providers.map { it.id } shouldBe listOf("curation", "television", "community")
		for (provider in providers) {
			val page = repo.screen(provider.id, provider.entryPoints.single().screenId)
			page.items.isNotEmpty() shouldBe true
		}
	}
	test("HTTP server-driven pagination yields all 85 records without duplicates") {
		val ids = mutableListOf<String>()
		var next: Int? = 0
		while (next != null) {
			val page = repo.screen("curation", "root", next)
			ids.addAll(page.items.map { it.id })
			next = page.nextIndex
		}
		ids.size shouldBe 85
		ids.distinct().size shouldBe 85
	}
	test("HTTP nested category returns viewer-authorized disjoint guide scopes") {
		val action = repo.screen("television", "root").items.first().action as ExtensionAction.OpenScreen
		val guide = repo.screen(action.extensionId, action.screenId).items.first().action as ExtensionAction.OpenGuide
		val sports = repo.guide(guide.extensionId, guide.scopeId)
		val news = repo.guide("television", "news")
		sports.channelIds.size shouldBe 2
		news.channelIds.size shouldBe 1
		sports.channelIds.intersect(news.channelIds).isEmpty() shouldBe true
	}
	test("HTTP removed absent malformed denied and unsupported providers fail without stale results") {
		repo.screen("television", "root").items.size shouldBe 2
		for (state in listOf("removed", "absent", "malformed", "version")) {
			mode(state)
			shouldThrowAny { repo.screen("television", "root") }
		}
		for (state in listOf("empty", "denied")) {
			mode(state);
			shouldThrowAny { repo.guide("television", "sports") }
		}
	}
	test("HTTP current-viewer authentication rejects missing or revoked token") {
		val denied = ExtensionSession(server, "viewer", "invalid-fixture-token")
		shouldThrowAny { ExtensionRepository({ denied }).discover() }
	}
	test("fixture accepts SDK repeated channelIds query values without dropping authorized programs") {
		val first = "00000000-0000-0000-0000-000000000001"
		val second = "00000000-0000-0000-0000-000000000002"
		val connection = URI(
			"$server/LiveTv/Programs?channelIds=$first&channelIds=$second"
		).toURL().openConnection() as java.net.HttpURLConnection
		try {
			connection.setRequestProperty("Authorization", extensionAuthorization(session.token))
			connection.responseCode shouldBe 200
			val body = kotlinx.serialization.json.Json.parseToJsonElement(
				connection.inputStream.bufferedReader().use { it.readText() }
			) as kotlinx.serialization.json.JsonObject
			val items = body.getValue("Items") as kotlinx.serialization.json.JsonArray
			items.size shouldBe 2
		} finally { connection.disconnect() }
	}
})
