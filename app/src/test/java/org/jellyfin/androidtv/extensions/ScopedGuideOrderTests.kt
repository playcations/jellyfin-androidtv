package org.jellyfin.androidtv.extensions

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.ui.livetv.sortScopedGuideChannels
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.time.LocalDateTime
import java.util.UUID

class ScopedGuideOrderTests : FunSpec({
	fun channel(name: String, favorite: Boolean, played: LocalDateTime?) = BaseItemDto(
		id = UUID.randomUUID(),
		type = BaseItemKind.TV_CHANNEL,
		name = name,
		userData = Json.decodeFromString<UserItemDataDto>(
			"""{"PlayCount":0,"PlaybackPositionTicks":0,"IsFavorite":$favorite,"Played":false,
				"Key":"fixture","ItemId":"00000000-0000-0000-0000-000000000001"}"""
		).copy(lastPlayedDate = played),
	)
	val first = channel("Alpha", false, LocalDateTime.of(2026, 1, 1, 0, 0))
	val favorite = channel("Beta", true, null)
	val recent = channel("Zulu", false, LocalDateTime.of(2026, 2, 1, 0, 0))
	test("scoped guide uses the same name ordering as the normal channel list") {
		sortScopedGuideChannels(listOf(recent, favorite, first), false, false) shouldBe listOf(first, favorite, recent)
	}
	test("favorites remain first within scoped guides") {
		sortScopedGuideChannels(listOf(recent, favorite, first), false, true) shouldBe listOf(favorite, first, recent)
	}
	test("last played ordering remains descending after a scoped reload") {
		sortScopedGuideChannels(listOf(favorite, first, recent), true, false) shouldBe listOf(recent, first, favorite)
	}
})
