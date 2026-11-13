package org.jellyfin.androidtv.ui.livetv

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

class TvManagerTests : FunSpec({
	beforeSpec {
		org.koin.core.context.startKoin {
			modules(org.koin.dsl.module {
				single<org.jellyfin.sdk.api.client.ApiClient> {
					io.mockk.mockk { io.mockk.every { baseUrl } returns "http://fixture"; io.mockk.every { accessToken } returns "fixture" }
				}
				single<org.jellyfin.androidtv.auth.repository.UserRepository> {
					io.mockk.mockk {
						io.mockk.every { currentUser } returns kotlinx.coroutines.flow.MutableStateFlow(
                    org.jellyfin.sdk.model.api.UserDto(
                        id = UUID.randomUUID(), hasPassword = false,
                        hasConfiguredPassword = false, hasConfiguredEasyPassword = false,
                    )
                )
					}
				}
			})
		}
		TvManager.getAllChannels() // Establish the viewer cache key before seeding test state.
	}
	afterSpec { org.koin.core.context.stopKoin() }

	val channelsField = TvManager::class.java.getDeclaredField("allChannels").apply { isAccessible = true }
	var previousChannels: Any? = null

	beforeTest {
		previousChannels = channelsField.get(null)
		channelsField.set(null, null)
	}

	afterTest {
		channelsField.set(null, previousChannels)
	}

	test("missing channel cache returns an empty list and no channel") {
		TvManager.getAllChannels() shouldBe emptyList()
		TvManager.getChannel(0) shouldBe null
		TvManager.getChannel(-1) shouldBe null
	}

	test("empty channel cache returns no channel") {
		channelsField.set(null, emptyList<BaseItemDto>())
		TvManager.getChannel(0) shouldBe null
	}

	test("legacy playback cache is erased on server viewer or token changes") {
		val api = org.koin.java.KoinJavaComponent.get<org.jellyfin.sdk.api.client.ApiClient>(
                    org.jellyfin.sdk.api.client.ApiClient::class.java
                )
		val users = org.koin.java.KoinJavaComponent.get<org.jellyfin.androidtv.auth.repository.UserRepository>(
                    org.jellyfin.androidtv.auth.repository.UserRepository::class.java
                )
		val oldUser = users.currentUser.value
		val channel = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.TV_CHANNEL)
		channelsField.set(null, listOf(channel))
		io.mockk.every { api.baseUrl } returns "http://other-server"
		TvManager.getAllChannels() shouldBe emptyList()
		channelsField.set(null, listOf(channel))
		io.mockk.every { api.accessToken } returns "new-token"
		TvManager.getAllChannels() shouldBe emptyList()
		channelsField.set(null, listOf(channel))
		io.mockk.every { users.currentUser } returns kotlinx.coroutines.flow.MutableStateFlow(null)
		TvManager.getAllChannels() shouldBe emptyList()
		io.mockk.every { api.baseUrl } returns "http://fixture"
		io.mockk.every { api.accessToken } returns "fixture"
		io.mockk.every { users.currentUser } returns kotlinx.coroutines.flow.MutableStateFlow(oldUser)
		TvManager.getAllChannels() shouldBe emptyList()
	}

	test("cached channel lookup preserves valid channels and rejects invalid indices") {
		val channel = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.TV_CHANNEL)
		channelsField.set(null, listOf(channel))
		TvManager.getChannel(0) shouldBe channel
		TvManager.getChannel(-1) shouldBe null
		TvManager.getChannel(1) shouldBe null
		TvManager.getChannel(Int.MAX_VALUE) shouldBe null
	}
})
