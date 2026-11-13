package org.jellyfin.androidtv.ui.livetv

import android.content.Context
import android.view.View
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.repository.ItemMutationRepository
import org.jellyfin.androidtv.databinding.LiveTvGuideBinding
import org.jellyfin.androidtv.ui.GuideChannelHeader
import org.jellyfin.androidtv.ui.navigation.ProvideRouter
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.composable.SettingsDialog
import org.jellyfin.androidtv.ui.settings.composable.SettingsRouterContent
import org.jellyfin.androidtv.ui.settings.routes
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

fun createNoProgramDataBaseItem(
	context: Context,
	channelId: UUID?,
	startDate: LocalDateTime?,
	endDate: LocalDateTime?
) = BaseItemDto(
	id = UUID.randomUUID(),
	type = BaseItemKind.FOLDER,
	mediaType = MediaType.UNKNOWN,
	name = context.getString(R.string.no_program_data),
	channelId = channelId,
	startDate = startDate,
	endDate = endDate,
)

fun LiveTvGuideFragment.toggleFavorite() {
	val header = mSelectedProgramView as? GuideChannelHeader
	val channel = header?.channel ?: return

	val itemMutationRepository by inject<ItemMutationRepository>()
	val dataRefreshService by inject<DataRefreshService>()

	lifecycleScope.launch {
		runCatching {
			val userData = itemMutationRepository.setFavorite(
				item = header.channel.id,
				favorite = !(channel.userData?.isFavorite ?: false)
			)

			header.channel = header.channel.copy(userData = userData)
			header.findViewById<View>(R.id.favImage).isVisible = userData.isFavorite
			dataRefreshService.lastFavoriteUpdate = Instant.now()
		}
	}
}

fun LiveTvGuideFragment.refreshSelectedProgram() {
	val api by inject<ApiClient>()
	val users by inject<org.jellyfin.androidtv.auth.repository.UserRepository>()
	val repository = org.jellyfin.androidtv.extensions.ExtensionRepository(api, users)
	val viewer = repository.currentSession() ?: return
	val selected = mSelectedProgram?.id ?: return

	viewLifecycleOwner.lifecycleScope.launch {
		runCatching {
			val item = withContext(Dispatchers.IO) {
				api.userLibraryApi.getItem(selected).content
			}
			require(repository.currentSession() == viewer)
			if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) || mSelectedProgram?.id != selected) return@launch
			mSelectedProgram = item
		}.onFailure { error ->
			Timber.e(error, "Unable to get program details")
		}

		detailUpdateInternal();
	}
}

fun LiveTvGuideFragment.addSettingsOptions(binding: LiveTvGuideBinding): MutableStateFlow<Boolean> {
	val visible = MutableStateFlow(false)

	binding.settingsOptions.setContent {
		val isVisible by visible.collectAsState(false)

		ProvideRouter(
			routes,
			Routes.LIVETV_GUIDE_OPTIONS
		) {
			SettingsDialog(
				visible = isVisible,
				onDismissRequest = {
					visible.value = false
					TvManager.forceReload()
					doLoad()
				}
			) {
				SettingsRouterContent()
			}
		}
	}

	return visible
}

fun LiveTvGuideFragment.addSettingsFilters(binding: LiveTvGuideBinding): MutableStateFlow<Boolean> {
	val visible = MutableStateFlow(false)

	binding.settingsFilters.setContent {
		val isVisible by visible.collectAsState(false)

		ProvideRouter(
			routes,
			Routes.LIVETV_GUIDE_FILTERS
		) {
			SettingsDialog(
				visible = isVisible,
				onDismissRequest = {
					visible.value = false
					TvManager.forceReload()
					doLoad()
				}
			) {
				SettingsRouterContent()
			}
		}
	}

	return visible
}

/** Revalidate visible scopes and erase the guide immediately when its viewer changes. */
fun LiveTvGuideFragment.observeExtensionViewer() {
	val users by inject<org.jellyfin.androidtv.auth.repository.UserRepository>()
	val api by inject<ApiClient>()
	val repository = org.jellyfin.androidtv.extensions.ExtensionRepository(api, users)
	val initial = repository.currentSession()
	viewLifecycleOwner.lifecycleScope.launch {
		viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
			launch {
				users.currentUser.collect { if (repository.currentSession() != initial) extensionUnavailable() }
			}
			if (arguments?.containsKey(org.jellyfin.androidtv.extensions.ExtensionScreenFragment.ARG_SCOPE) == true) launch {
				while (true) {
					kotlinx.coroutines.delay(15000)
					runCatching {
						require(repository.currentSession() == initial)
						val scope = repository.guide(requireArguments().getString(org.jellyfin.androidtv.extensions.ExtensionScreenFragment.ARG_EXTENSION)!!, requireArguments().getString(org.jellyfin.androidtv.extensions.ExtensionScreenFragment.ARG_SCOPE)!!)
						require(scope.channelIds == guideSession.authorizedIds)
					}.onFailure { extensionUnavailable() }
				}
			}
		}
	}
}
