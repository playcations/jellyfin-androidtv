package org.jellyfin.androidtv.extensions

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.koin.android.ext.android.inject

/** imageUrl is intentionally not fetched; SDK media artwork stays on the connected server. */
class ExtensionPresenterSelector : PresenterSelector() {
	private val text = object : Presenter() {
		override fun onCreateViewHolder(parent: ViewGroup): ViewHolder = ViewHolder(Button(parent.context).apply {
			layoutParams = ViewGroup.LayoutParams(300, 120)
			isFocusable = true
			setTextColor(Color.WHITE)
			setBackgroundColor(Color.rgb(40, 48, 64))
			setOnFocusChangeListener { _, focus ->
				setBackgroundColor(if (focus) Color.rgb(0, 110, 155) else Color.rgb(40, 48, 64))
				scaleX = if (focus) 1.04f else 1f
				scaleY = scaleX
			}
		})
		override fun onBindViewHolder(holder: ViewHolder, item: Any?) { (holder.view as Button).text = when (item) {
			is ExtensionEntry -> item.title
			is ExtensionCatalogCard -> "Extensions"
			else -> item.toString()
		} }
		override fun onUnbindViewHolder(holder: ViewHolder) = Unit
	}
	private val card = object : Presenter() {
		private val delegate = CardPresenter()
		override fun onCreateViewHolder(parent: ViewGroup) = delegate.onCreateViewHolder(parent)
		override fun onBindViewHolder(holder: ViewHolder, item: Any?) = delegate.onBindViewHolder(holder, BaseItemDtoBaseRowItem(requireNotNull((item as ExtensionEntry).media)))
		override fun onUnbindViewHolder(holder: ViewHolder) = delegate.onUnbindViewHolder(holder)
	}
	override fun getPresenter(item: Any?): Presenter = if (item is ExtensionEntry && item.media != null) card else text
}
object ExtensionCatalogCard
private data class MorePage(val start: Int) { override fun toString() = "Load more" }

class ExtensionActionDispatcher(private val fragment: androidx.fragment.app.Fragment, private val validSource: () -> Boolean = { true }) {
	private val api by fragment.inject<ApiClient>()
	private val users by fragment.inject<UserRepository>()
	private val navigation by fragment.inject<NavigationRepository>()
	private val playback by fragment.inject<PlaybackLauncher>()
	private val repository get() = ExtensionRepository(api, users)
	private fun requireActive() { check(validSource() && fragment.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) { "Source screen closed" } }
	suspend fun dispatch(action: ExtensionAction, expected: ExtensionSession, provider: String? = null) {
		ExtensionActionExecutor(
			repository,
			fetchItem = { api.userLibraryApi.getItem(it).content },
			openScreen = { extension, screen, viewer -> requireActive(); navigation.navigate(Destinations.extensionScreen(extension, screen, viewer)) },
			openItem = { requireActive(); navigation.navigate(Destinations.itemDetails(it)) },
			playItem = { requireActive(); playback.launch(fragment.requireContext(), listOf(it)) },
			openGuide = { extension, scope, viewer -> requireActive(); navigation.navigate(Destinations.extensionGuide(extension, scope, viewer)) },
		).dispatch(action, expected, provider)
	}

}

/** One native paged renderer for every provider. Fragment back stack retains selection, never media data. */
class ExtensionScreenFragment : VerticalGridSupportFragment() {
	private val api by inject<ApiClient>()
	private val users by inject<UserRepository>()
	private val navigation by inject<NavigationRepository>()
	private val repo by lazy { ExtensionRepository(api, users) }
	private val nativeGrid = object : VerticalGridPresenter() {
		var currentGrid: VerticalGridView? = null
		override fun initializeGridViewHolder(holder: VerticalGridPresenter.ViewHolder) {
			super.initializeGridViewHolder(holder)
			currentGrid = holder.gridView
		}
		fun columns(count: Int) {
			numberOfColumns = count
			currentGrid?.setNumColumns(count)
		}
	}
	private val objects = ArrayObjectAdapter(ExtensionPresenterSelector())
	private var selected = 0
	private var loadedPages = 1
	private var busy = false
	private var session: ExtensionSession? = null
	private var refresh: kotlinx.coroutines.Job? = null
	private var viewerObserver: kotlinx.coroutines.Job? = null
	private var actionJob: kotlinx.coroutines.Job? = null
	private val work = ExtensionWorkState()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		selected = savedInstanceState?.getInt("selection") ?: selected
		loadedPages = savedInstanceState?.getInt("pages", 1) ?: loadedPages
		title = "Extensions"
		setGridPresenter(nativeGrid.apply { numberOfColumns = 4 })
		adapter = objects
		setOnItemViewSelectedListener { _, item, _, _ -> if (item != null) selected = objects.indexOf(item).coerceAtLeast(0) }
		setOnItemViewClickedListener { _, item, _, _ ->
			val generation = work.current()
			if (!busy && work.accepts(generation)) actionJob = lifecycleScope.launch {
				work.requireCurrent(generation)
				busy = true
				try {
					when (item) {
						is MorePage -> loadPage(item.start, generation)
						is ExtensionEntry -> ExtensionActionDispatcher(this@ExtensionScreenFragment) { work.accepts(generation) }.dispatch(item.action, requireNotNull(session), item.extensionId)
					}
				} catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { unavailable() } finally { if (work.accepts(generation)) busy = false }
			}
		}
	}
	private fun unavailable() {
		work.invalidate()
		actionJob?.cancel()
		refresh?.cancel()
		viewerObserver?.cancel()
		busy = false
		objects.clear()
		title = "Extension unavailable"
		if (isAdded) Toast.makeText(requireContext(), "Extension unavailable or access changed. Press Back to return.", Toast.LENGTH_LONG).show()
	}
	override fun onResume() {
		super.onResume()
		val generation = work.resume()
		refresh?.cancel()
		refresh = lifecycleScope.launch {
			busy = true
			val restore = selected
			val pages = loadedPages.coerceIn(1, 25000)
			objects.clear()
			try {
				session = requireNotNull(repo.currentSession()).also { require(matchesExtensionViewer(requireArguments(), it)) }
				if (arguments?.getString(ARG_EXTENSION) == null) {
					val extensions = repo.discover(requireNotNull(session))
					work.requireCurrent(generation)
					for (extension in extensions) for (link in extension.entryPoints) {
						objects.add(ExtensionEntry("${extension.id}:${link.id}", "${extension.title} · ${link.title}", null, ExtensionAction.OpenScreen(extension.id, link.screenId)))
					}
					if (objects.size() == 0) title = "No extensions available"
				} else {
					loadedPages = 0
					var start: Int? = 0
					repeat(pages) { if (start != null) start = loadPage(requireNotNull(start), generation) }
				}
				work.requireCurrent(generation)
				setSelectedPosition(restore.coerceIn(0, (objects.size() - 1).coerceAtLeast(0)))
			} catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { unavailable() } finally { if (work.accepts(generation)) busy = false }
		}
		// Logout/user switch blanks the currently displayed provider immediately.
		viewerObserver?.cancel()
		viewerObserver = lifecycleScope.launch {
			launch { users.currentUser.collect { if (session != null && repo.currentSession() != session) unavailable() } }
			while (true) {
				kotlinx.coroutines.delay(15000)
				try {
					val expected = session ?: continue
					val extension = arguments?.getString(ARG_EXTENSION)
					if (extension == null) repo.discover(expected)
					else repo.screen(extension, requireArguments().getString(ARG_SCREEN)!!, limit = 1, session = expected)
				} catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
				catch (_: Exception) { unavailable() }
			}
		}
	}
	private suspend fun loadPage(start: Int, generation: Long): Int? {
		val page = repo.screen(requireArguments().getString(ARG_EXTENSION)!!, requireArguments().getString(ARG_SCREEN)!!, start, session = requireNotNull(session))
		work.requireCurrent(generation)
		title = page.title
		nativeGrid.columns(if (page.view == "categories") 1 else 4)
		if (objects.size() > 0 && objects.get(objects.size() - 1) is MorePage) objects.removeItems(objects.size() - 1, 1)
		for (item in page.items) objects.add(item)
		page.nextIndex?.let { objects.add(MorePage(it)) }
		loadedPages++
		return page.nextIndex
	}
	override fun onPause() { super.onPause(); work.invalidate(); actionJob?.cancel(); refresh?.cancel(); viewerObserver?.cancel(); objects.clear(); busy = false }
	override fun onSaveInstanceState(outState: Bundle) {
		outState.putInt("selection", selected)
		outState.putInt("pages", loadedPages)
		super.onSaveInstanceState(outState)
	}
	companion object {
		const val ARG_EXTENSION = "extensionId"
		const val ARG_SCREEN = "extensionScreenId"
		const val ARG_SCOPE = "extensionScopeId"
		const val ARG_SERVER = "extensionServer"
		const val ARG_USER = "extensionUser"
	}
}
fun matchesExtensionViewer(arguments: Bundle, session: ExtensionSession): Boolean =
	arguments.getString(ExtensionScreenFragment.ARG_SERVER) == session.server && arguments.getString(ExtensionScreenFragment.ARG_USER) == session.user
fun Bundle.putExtensionViewer(session: ExtensionSession) {
	putString(ExtensionScreenFragment.ARG_SERVER, session.server)
	putString(ExtensionScreenFragment.ARG_USER, session.user)
}
