package org.jellyfin.androidtv.extensions

import kotlinx.coroutines.CancellationException

/** Invalidates late results even when a provider/transport does not cooperate with job cancellation. */
class ExtensionWorkState {
	private var generation = 0L
	private var available = false
	fun resume(): Long {
		available = true
		return ++generation
	}
	fun invalidate() {
		available = false
		generation++
	}
	fun current() = generation
	fun accepts(generation: Long) = available && generation == this.generation
	fun requireCurrent(generation: Long) {
		if (!accepts(generation)) throw CancellationException("Extension work invalidated")
	}
}
