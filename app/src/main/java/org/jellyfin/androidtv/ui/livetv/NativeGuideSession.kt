package org.jellyfin.androidtv.ui.livetv

import org.jellyfin.androidtv.extensions.GuideState
import org.jellyfin.androidtv.util.apiclient.EmptyResponse
import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.LocalDateTime
import java.util.UUID
import java.util.function.Function

/** Owned by a single fragment; callbacks from previous refreshes cannot repopulate its state. */
class NativeGuideSession {
	private val state = GuideState()
	val authorizedIds get() = state.authorizedIds
	val generation get() = state.generation
	val isUnavailable get() = state.unavailable
	fun authorize(generation: Long, ids: Set<UUID>) = state.authorize(generation, ids)
	fun clear() { state.reload() }
	fun invalidate() { state.invalidate() }
	fun getAllChannels() = state.channels
	fun getChannel(index: Int) = state.channels.getOrNull(index)
	fun getChannel(id: UUID) = state.channels.firstOrNull { it.id == id }
	fun loadAllChannels(fragment: LiveTvGuideFragment, callback: Function<Int, Void?>) {
		if (state.unavailable) { fragment.extensionUnavailable(); return }
		val generation = state.reload()
		loadLiveTvChannels(fragment) { channels ->
			if (state.generation != generation) return@loadLiveTvChannels
			if (channels == null) { fragment.extensionUnavailable(); return@loadLiveTvChannels }
			state.channels(generation, channels.orEmpty())
			callback.apply(state.channels.indexOfFirst { it.id == TvManager.getLastLiveTvChannel() }.coerceAtLeast(0))
		}
	}
	fun getProgramsAsync(fragment: LiveTvGuideFragment, start: Int, end: Int, from: LocalDateTime, to: LocalDateTime, callback: EmptyResponse) {
		val generation = state.generation
		val channels = state.channels.drop(start.coerceAtLeast(0)).take((end - start + 1).coerceAtLeast(0)).map { it.id }.toTypedArray()
		if (channels.isEmpty()) { callback.onResponse(); return }
		getPrograms(fragment, channels, from.withMinute(if (from.minute >= 30) 30 else 0).withSecond(0).withNano(0), to.minusSeconds(1)) { programs ->
			if (state.generation != generation) return@getPrograms
			if (programs == null) {
				clear()
				fragment.extensionUnavailable()
			} else {
				state.programs(generation, programs)
				callback.onResponse()
			}
		}
	}
	fun getProgramsForChannel(channel: UUID, filters: GuideFilters): List<BaseItemDto> {
		val programs = state.programs(channel)
		return if (!filters.any() || programs.any { filters.passesFilter(it) }) programs else emptyList()
	}
}
