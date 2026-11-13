package org.jellyfin.androidtv.extensions

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.di.KoinInitializer
import org.jellyfin.androidtv.ui.browsing.DestinationFragmentView
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationAction
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.UserDto
import org.koin.android.ext.android.inject
import java.util.UUID

private const val FIXTURE_LOADING_TEXT_SIZE_SP = 28f

/** Debug-only, emulator-only entry for exercising the real renderer and guide against isolated fixtures. */
class ExtensionFixtureActivity : FragmentActivity() {
	private val api by inject<ApiClient>()
	private val users by inject<UserRepository>()
	private val navigation by inject<NavigationRepository>()
	override fun onCreate(state: Bundle?) {
		super.onCreate(state)
		setContentView(
			android.widget.TextView(this).apply {
				setText(R.string.loading)
				textSize = FIXTURE_LOADING_TEXT_SIZE_SP
			}
		)
		lifecycleScope.launch {
			kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
				androidx.startup.AppInitializer.getInstance(applicationContext).initializeComponent(KoinInitializer::class.java)
			}
			org.jellyfin.androidtv.SessionInitializer.restored.await()
			val privateFile = java.io.File(filesDir, "fixture-session.json")
			val config = if (privateFile.exists()) org.json.JSONObject(privateFile.readText()) else null
			val server = config?.getString("server") ?: intent.getStringExtra("fixtureServer") ?: "http://10.0.2.2:18096"
			val token = config?.getString("token") ?: "fixture-viewer-token"
			val user = config?.getString("userId") ?: "10000000-0000-0000-0000-000000000001"
			require(java.net.URI(server).host in setOf("10.0.2.2", "127.0.0.1", "localhost"))
			api.update(baseUrl = server, accessToken = token)
			users.setCurrentUser(
				UserDto(
					id = UUID.fromString(user),
					name = "Fixture viewer",
					hasPassword = false,
					hasConfiguredPassword = false,
					hasConfiguredEasyPassword = false,
				)
			)
			val host = DestinationFragmentView(this@ExtensionFixtureActivity)
			setContentView(host)
			onBackPressedDispatcher.addCallback(
				this@ExtensionFixtureActivity,
				object : OnBackPressedCallback(true) {
					override fun handleOnBackPressed() { if (!navigation.goBack()) finish() }
				}
			)
			val session = requireNotNull(ExtensionRepository(api, users).currentSession())
			navigation.reset(Destinations.extensionScreen(session = session), true)
			navigation.currentAction.collect {
				when (it) {
					is NavigationAction.NavigateFragment -> host.navigate(it)
					NavigationAction.GoBack -> host.goBack()
					else -> Unit
				}
			}
		}
	}
}
