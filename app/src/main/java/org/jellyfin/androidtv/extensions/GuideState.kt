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
	var authorizedIds: Set<UUID>? = null
		private set
	var unavailable: Boolean = false
		private set
	fun invalidate() {
		unavailable = true;
		clear()
	}
	fun clear(): Long = reset(true)
	fun reload(): Long = reset(false)
	private fun reset(resetScope: Boolean): Long {
		channels = emptyList()
		programs = emptyMap()
		if (resetScope) authorizedIds = null
		return ++generation
	}

	/** Establish before channel I/O; subsequent reloads/validators may only confirm the same scope. */
	fun authorize(generation: Long, ids: Set<UUID>): Boolean {
		if (unavailable || generation != this.generation) return false
		require(ids.isNotEmpty() && (authorizedIds == null || authorizedIds == ids)) { "Guide permissions changed" }
		authorizedIds = ids.toSet()
		return true
	}
	fun channels(
		generation: Long,
		value: Collection<BaseItemDto>
	) { if (generation == this.generation) channels = value.toList() }
	fun programs(generation: Long, value: Collection<BaseItemDto>) {
		if (generation != this.generation) return
		val allowed = channels.map { it.id }.toSet()
		programs = value.filter { it.channelId in allowed }.groupBy { requireNotNull(it.channelId) }
	}
	fun programs(channel: UUID) = programs[channel].orEmpty()
}
