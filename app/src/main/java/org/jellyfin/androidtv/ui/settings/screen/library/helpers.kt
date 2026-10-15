package org.jellyfin.androidtv.ui.settings.screen.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.map
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.compose.koinInject
import java.util.UUID

@Composable
fun rememberUserView(itemId: UUID): BaseItemDto? {
	val userViewsRepository = koinInject<UserViewsRepository>()
	val userView by remember {
		// Grids inside folders (e.g. plugin channel folders) open these settings with an id that is not a top-level view
		userViewsRepository.views.map { views -> views.firstOrNull { view -> view.id == itemId } }
	}.collectAsState(null)
	return userView
}
