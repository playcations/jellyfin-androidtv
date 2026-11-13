package org.jellyfin.androidtv.extensions

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

class ExtensionActionTests : FunSpec({
	val itemId = UUID.fromString("00000000-0000-0000-0000-000000000001")
	val original = ExtensionSession("http://fixture", "viewer", "token")
	var viewer: ExtensionSession? = original
	var revoked = false
	var scopeEmpty = false
	var mediaDenied = false
	val stack = mutableListOf("category")
	var fetched = 0
	val repo = ExtensionRepository({ viewer }, ExtensionTransport { _, path ->
		val provider = """{"id":"provider","title":"Provider","version":"1","entryPoints":[],"homeRows":[]}"""
		val providers = if (revoked) "[]" else "[$provider]"
		val channels = if (scopeEmpty) "[]" else "[\"$itemId\"]"
		when {
			path == ExtensionRepository.ROOT -> """{"protocolVersion":1,"extensions":$providers}"""
			"guide-scopes" in path -> """{"protocolVersion":1,"id":"sports","title":"Sports","channelIds":$channels}"""
			else -> """{"protocolVersion":1,"id":"nested","title":"Nested","view":"categories",
				"startIndex":0,"totalRecordCount":0,"items":[]}"""
		}
	})
	val executor = ExtensionActionExecutor(
		repo,
		fetchItem = {
			fetched++
			if (mediaDenied) error("403")
			BaseItemDto(id = it, type = BaseItemKind.MOVIE)
		},
		openScreen = { provider, screen, _ -> stack.add("screen:$provider:$screen") },
		openItem = { stack.add("item:$it") },
		playItem = { stack.add("play:${it.id}") },
		openGuide = { provider, scope, _ -> stack.add("guide:$provider:$scope") },
	)
	beforeTest {
		viewer = original
		revoked = false
		scopeEmpty = false
		mediaDenied = false
		fetched = 0
		stack.clear()
		stack.add(
			"category"
		)
	}
	test("openScreen is one generic native route") {
		executor.dispatch(ExtensionAction.OpenScreen("provider", "nested"), original, "provider")
		stack shouldBe listOf("category", "screen:provider:nested")
	}
	test("openItem and playItem refetch authorized media and reuse native callbacks") {
		executor.dispatch(ExtensionAction.OpenItem(itemId), original, "provider")
		executor.dispatch(ExtensionAction.PlayItem(itemId), original, "provider")
		fetched shouldBe 2
		stack shouldBe listOf("category", "item:$itemId", "play:$itemId")
	}
	test("guide action pushes one guide after category so one Back restores category") {
		executor.dispatch(ExtensionAction.OpenGuide("provider", "sports"), original, "provider")
		stack shouldBe listOf("category", "guide:provider:sports")
		stack.removeLast()
		stack.last() shouldBe "category"
	}
	test("removed provider blocks stale media and playback actions") {
		revoked = true
		shouldThrowAny { executor.dispatch(ExtensionAction.OpenItem(itemId), original, "provider") }
		shouldThrowAny { executor.dispatch(ExtensionAction.PlayItem(itemId), original, "provider") }
		fetched shouldBe 0
		stack shouldBe listOf("category")
	}
	test("empty denied or removed guide never navigates") {
		scopeEmpty = true
		shouldThrowAny { executor.dispatch(ExtensionAction.OpenGuide("provider", "sports"), original, "provider") }
		scopeEmpty = false
		revoked = true
		shouldThrowAny { executor.dispatch(ExtensionAction.OpenGuide("provider", "sports"), original, "provider") }
		stack shouldBe listOf("category")
	}
	test("revoked media permission and changed viewer cannot navigate") {
		mediaDenied = true
		shouldThrowAny { executor.dispatch(ExtensionAction.PlayItem(itemId), original, "provider") }
		mediaDenied = false
		viewer = null
		shouldThrowAny { executor.dispatch(ExtensionAction.OpenScreen("provider", "nested"), original, "provider") }
		stack shouldBe listOf("category")
	}
	test("provider revoked during a delayed media fetch cannot open details or launch playback") {
		val delayed = ExtensionActionExecutor(
			repo,
			fetchItem = {
				revoked = true
				BaseItemDto(id = it, type = BaseItemKind.MOVIE)
			},
			openScreen = { _, _, _ -> error("Unexpected screen") },
			openItem = { stack.add("item:$it") },
			playItem = { stack.add("play:${it.id}") },
			openGuide = { _, _, _ -> error("Unexpected guide") },
		)
		for (action in listOf(ExtensionAction.OpenItem(itemId), ExtensionAction.PlayItem(itemId))) {
			revoked = false
			shouldThrowAny { delayed.dispatch(action, original, "provider") }
			stack shouldBe listOf("category")
		}
	}
})
