package org.jellyfin.androidtv.extensions

import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto

class ExtensionProtocolTests : FunSpec({
	val id = "00000000-0000-0000-0000-000000000001"
	fun discovery(vararg providers: String) = """{"protocolVersion":1,"extensions":[${providers.joinToString { """{"id":"$it","title":"$it title","version":"1.0.0","entryPoints":[{"id":"entry","title":"Browse","screenId":"root"}],"homeRows":[{"id":"home","title":"Featured","screenId":"root"}]}""" }}]}"""
	fun page(provider: String = "alpha", act: String = """{"type":"openScreen","extensionId":"alpha","screenId":"nested"}""", start: Int = 0, total: Int = 1, view: String = "grid", media: String = "null") = """{"protocolVersion":1,"id":"root","title":"Root","view":"$view","startIndex":$start,"totalRecordCount":$total,"items":[{"id":"one","title":"One","media":$media,"action":$act}]}"""
	fun parseAction(action: String) = ExtensionProtocol.action(Json.parseToJsonElement(action).jsonObject, "alpha")

	test("three unrelated providers use exactly the same discovery and renderer model") {
		val providers = ExtensionProtocol.discovery(discovery("alpha", "beta", "gamma"))
		providers.size shouldBe 3
		for (provider in providers) {
			val p = ExtensionProtocol.page(page(provider.id, """{"type":"openScreen","extensionId":"${provider.id}","screenId":"nested"}"""), provider.id, "root", 0, 40)
			p.items.single().action shouldBe ExtensionAction.OpenScreen(provider.id, "nested")
		}
	}
	test("malformed provider is isolated from other providers") {
		ExtensionProtocol.discovery(discovery("alpha", "bad/id", "gamma")).map { it.id } shouldBe listOf("alpha", "gamma")
	}
	test("non-object and duplicate providers are isolated while unambiguous providers survive") {
		ExtensionProtocol.discovery(discovery("alpha", "gamma").replace("\"extensions\":[", "\"extensions\":[null,42," )).map { it.id } shouldBe listOf("alpha", "gamma")
		ExtensionProtocol.discovery(discovery("alpha", "alpha", "gamma")).map { it.id } shouldBe listOf("gamma")
	}

	test("envelope versions absent unknown or wrongly typed fail closed") {
		for (body in listOf("{}", discovery("alpha").replace("\"protocolVersion\":1", "\"protocolVersion\":2"), discovery("alpha").replace("\"protocolVersion\":1", "\"protocolVersion\":\"1\""))) shouldThrowAny { ExtensionProtocol.discovery(body) }
	}
	test("path traversal external URLs controls and oversized IDs are rejected") {
		for (bad in listOf("", "..", ".", "../abc", "https://example.com", "a?token=x", "a/b", "a\\b", "a\n", "x".repeat(201))) shouldThrowAny { ExtensionProtocol.identifier(bad) }
		ExtensionProtocol.identifier("stable-id_1:abc.def") shouldBe "stable-id_1:abc.def"
	}
	test("four typed actions decode without provider switches") {
		parseAction("""{"type":"openItem","itemId":"$id"}""") shouldBe ExtensionAction.OpenItem(UUID.fromString(id))
		parseAction("""{"type":"playItem","itemId":"$id"}""") shouldBe ExtensionAction.PlayItem(UUID.fromString(id))
		parseAction("""{"type":"openScreen","extensionId":"alpha","screenId":"root"}""") shouldBe ExtensionAction.OpenScreen("alpha", "root")
		parseAction("""{"type":"openGuide","extensionId":"alpha","scopeId":"sports"}""") shouldBe ExtensionAction.OpenGuide("alpha", "sports")
	}
	test("unknown actions malformed item UUIDs and cross-provider actions are rejected") {
		for (action in listOf("""{"type":"javascript","url":"https://evil.test"}""", """{"type":"openItem","itemId":"../evil"}""", """{"type":"openGuide","extensionId":"beta","scopeId":"root"}""")) shouldThrowAny { parseAction(action) }
	}
	test("normal Jellyfin BaseItemDto is decoded inside camelCase envelope") {
		ExtensionProtocol.page(page(media = """{"Id":"$id","Name":"A movie","Type":"Movie","MediaType":"Video"}"""), "alpha", "root", 0, 40).items.single().media?.name shouldBe "A movie"
	}
	test("grid and category layouts share paged model") {
		for (view in listOf("grid", "categories")) ExtensionProtocol.page(page(view = view), "alpha", "root", 0, 40).view shouldBe view
		shouldThrowAny { ExtensionProtocol.page(page(view = "webview"), "alpha", "root", 0, 40) }
	}
	test("pagination advances from server offset and terminates at exact total") {
		ExtensionProtocol.page(page(total = 2), "alpha", "root", 0, 40).nextIndex shouldBe 1
		ExtensionProtocol.page(page(start = 1, total = 2), "alpha", "root", 1, 40).nextIndex shouldBe null
	}
	test("pagination rejects non-progress mismatched offsets totals and oversized requests") {
		for (body in listOf(page(start = 1), page(total = 0))) shouldThrowAny { ExtensionProtocol.page(body, "alpha", "root", 0, 40) }
		shouldThrowAny { ExtensionProtocol.page(page(), "alpha", "root", -1, 40) }
		shouldThrowAny { ExtensionProtocol.page(page(), "alpha", "root", 0, 101) }
		shouldThrowAny { ExtensionProtocol.page(page().replace("\"id\":\"root\"", "\"id\":\"other\""), "alpha", "root", 0, 40) }
		shouldThrowAny { ExtensionProtocol.page("""{"protocolVersion":1,"id":"root","title":"Root","view":"grid","startIndex":0,"totalRecordCount":10,"items":[]}""", "alpha", "root", 0, 40) }
	}
	test("scope only accepts nonempty bounded authorized UUIDs") {
		val scope = """{"protocolVersion":1,"id":"sports","title":"Sports","channelIds":["$id"]}"""
		ExtensionProtocol.scope(scope, "sports").channelIds shouldBe setOf(UUID.fromString(id))
		for (body in listOf(scope.replace("\"$id\"", ""), scope.replace(id, "bad-id"), scope.replace("\"protocolVersion\":1", "\"protocolVersion\":9"))) shouldThrowAny { ExtensionProtocol.scope(body, "sports") }
		shouldThrowAny { ExtensionProtocol.scope(scope, "news") }
	}
	test("guide count page size duplicate entries and malformed media are bounded") {
		val ids = List(10001) { "\"$id\"" }.joinToString(",")
		shouldThrowAny { ExtensionProtocol.scope("""{"protocolVersion":1,"id":"sports","title":"Sports","channelIds":[$ids]}""", "sports") }
		val single = Json.parseToJsonElement(page()).jsonObject
		val duplicate = JsonObject(single + ("items" to JsonArray(List(2) { single.getValue("items").jsonArray.first() })) + ("totalRecordCount" to JsonPrimitive(2)))
		shouldThrowAny { ExtensionProtocol.page(duplicate.toString(), "alpha", "root", 0, 1) }
		shouldThrowAny { ExtensionProtocol.page(duplicate.toString(), "alpha", "root", 0, 40) }
		shouldThrowAny { ExtensionProtocol.page(page(media = "{}"), "alpha", "root", 0, 40) }
	}
	test("authorization rejects header injection before issuing network requests") {
		for (bad in listOf("", "token\r\nX-Other: leaked", "token\"", "token\\")) shouldThrowAny { extensionAuthorization(bad) }
	}

	test("per-guide cache generations isolate scopes and reject late callbacks") {
		val a = GuideState()
		val b = GuideState()
		val ch = BaseItemDto(id = UUID.fromString(id), type = org.jellyfin.sdk.model.api.BaseItemKind.TV_CHANNEL)
		val initial = a.clear()
		a.channels(initial, listOf(ch))
		b.channels(b.generation, emptyList())
		b.channels shouldBe emptyList()
		a.clear()
		a.channels(initial, listOf(ch))
		a.channels shouldBe emptyList()
	}
	test("program cache drops channels outside the current guide") {
		val a = GuideState()
		val ch = UUID.fromString(id)
		val other = UUID.randomUUID()
		a.channels(a.generation, listOf(BaseItemDto(id = ch, type = org.jellyfin.sdk.model.api.BaseItemKind.TV_CHANNEL)))
		a.programs(a.generation, listOf(BaseItemDto(id = UUID.randomUUID(), channelId = ch, type = org.jellyfin.sdk.model.api.BaseItemKind.PROGRAM), BaseItemDto(id = UUID.randomUUID(), channelId = other, type = org.jellyfin.sdk.model.api.BaseItemKind.PROGRAM)))
		a.programs(ch).size shouldBe 1
		a.programs(other) shouldBe emptyList()
		a.clear()
		a.programs(ch) shouldBe emptyList()
	}
	test("repository rechecks removal and revocation before pages and scopes") {
		val session = ExtensionSession("http://localhost", "viewer", "fake")
		var available = true
		var requests = 0
		val repo = ExtensionRepository({ session }, ExtensionTransport { _, path ->
			requests++
			if (path == ExtensionRepository.ROOT) discovery(*if (available) arrayOf("alpha") else emptyArray()) else page()
		})
		repo.screen("alpha", "root").items.size shouldBe 1
		available = false
		shouldThrowAny { repo.screen("alpha", "root") }
		shouldThrowAny { repo.guide("alpha", "sports") }
		requests shouldBe 4
	}
	test("in-flight responses are discarded after logout server change or token rotation") {
		val original = ExtensionSession("http://localhost", "viewer", "fake")
		for (replacement in listOf(null, ExtensionSession("http://other", "viewer", "fake"), ExtensionSession("http://localhost", "other", "fake"), ExtensionSession("http://localhost", "viewer", "new"))) {
			var active: ExtensionSession? = original
			val repo = ExtensionRepository({ active }, ExtensionTransport { _, _ -> active = replacement; discovery("alpha") })
			shouldThrowAny { repo.discover(original) }
		}
	}
	test("missing bridge denied malformed and unsupported responses never yield cached discovery") {
		val session = ExtensionSession("http://localhost", "viewer", "fake")
		var payload = discovery("alpha")
		val repo = ExtensionRepository({ session }, ExtensionTransport { _, _ -> if (payload == "404" || payload == "403") error(payload) else payload })
		repo.discover().size shouldBe 1
		for (bad in listOf("404", "403", "not json", "{}", discovery("alpha").replace("\"protocolVersion\":1", "\"protocolVersion\":8"))) { payload = bad; shouldThrowAny { repo.discover() } }
	}
	test("HTTP transport authenticates only fixed same-origin routes and refuses redirects") {
		val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		var leaked = false
		server.createContext("/ClientExtensions/v1") { exchange ->
			exchange.requestHeaders.getFirst("Authorization") shouldBe extensionAuthorization("fixture")
			exchange.responseHeaders.add("Location", "/external")
			exchange.sendResponseHeaders(302, -1); exchange.close()
		}
		server.createContext("/external") { leaked = true; it.sendResponseHeaders(200, -1); it.close() }
		server.start()
		try {
			val transport = ServerExtensionTransport()
			val session = ExtensionSession("http://127.0.0.1:${server.address.port}", "viewer", "fixture")
			shouldThrowAny { transport.get(session, ExtensionRepository.ROOT) }
			leaked shouldBe false
			shouldThrowAny { transport.get(session, "https://external.test") }
		} finally { server.stop(0) }
	}
})
