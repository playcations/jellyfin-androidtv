package org.jellyfin.androidtv

import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.startup.AppInitializer
import androidx.startup.Initializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.di.KoinInitializer

@Suppress("unused")
class SessionInitializer : Initializer<Unit> {
	override fun create(context: Context) {
		val koin = AppInitializer.getInstance(context)
			.initializeComponent(KoinInitializer::class.java)
			.koin

		// Restore system session
		ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
			try {
				koin.get<SessionRepository>().restoreSession(destroyOnly = false)
			} finally { restored.complete(Unit) }
		}
	}

	companion object {
		/** Awaitable startup boundary used by the debug-only isolated fixture harness. */
		val restored = kotlinx.coroutines.CompletableDeferred<Unit>()
	}

	override fun dependencies() = listOf(KoinInitializer::class.java)
}
