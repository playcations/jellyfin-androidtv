package org.jellyfin.androidtv.extensions

import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

/** The single action boundary shared by home and screen cards. All navigation callbacks occur after validation. */
class ExtensionActionExecutor(
	private val repository: ExtensionRepository,
	private val fetchItem: suspend (UUID) -> BaseItemDto,
	private val openScreen: (String, String, ExtensionSession) -> Unit,
	private val openItem: (UUID) -> Unit,
	private val playItem: (BaseItemDto) -> Unit,
	private val openGuide: (String, String, ExtensionSession) -> Unit,
) {
	suspend fun dispatch(action: ExtensionAction, expected: ExtensionSession, provider: String? = null) {
		require(repository.currentSession() == expected) { "Viewer changed" }
		if (provider != null) require(repository.discover(expected).any { it.id == provider }) { "Extension removed or access revoked" }
		when (action) {
			is ExtensionAction.OpenScreen -> {
				repository.screen(action.extensionId, action.screenId, session = expected)
				openScreen(action.extensionId, action.screenId, expected)
			}
			is ExtensionAction.OpenItem -> {
				fetchItem(action.itemId)
				require(repository.currentSession() == expected)
				openItem(action.itemId)
			}
			is ExtensionAction.PlayItem -> {
				val item = fetchItem(action.itemId)
				require(repository.currentSession() == expected)
				playItem(item)
			}
			is ExtensionAction.OpenGuide -> {
				repository.guide(action.extensionId, action.scopeId, expected)
				openGuide(action.extensionId, action.scopeId, expected)
			}
		}
	}
}
