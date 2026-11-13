package org.jellyfin.androidtv.extensions

import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

/** Per-guide state. No scoped content is ever stored in TvManager's legacy playback cache. */
class GuideState {
	var channels: List<BaseItemDto> = emptyList()
		private set
	private var programs: Map<UUID, List<BaseItemDto>> = emptyMap()
	var generation: Long = 0
		private set
	fun clear(): Long {
		channels = emptyList()
		programs = emptyMap()
		return ++generation
	}
	fun channels(generation: Long, value: Collection<BaseItemDto>) { if (generation == this.generation) channels = value.toList() }
	fun programs(generation: Long, value: Collection<BaseItemDto>) {
		if (generation != this.generation) return
		val allowed = channels.map { it.id }.toSet()
		programs = value.filter { it.channelId in allowed }.groupBy { requireNotNull(it.channelId) }
	}
	fun programs(channel: UUID) = programs[channel].orEmpty()
}
