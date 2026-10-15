package org.jellyfin.androidtv.ui.home

import android.content.Context
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Row
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.data.model.ForYouSection
import org.jellyfin.androidtv.data.querying.GetForYouSectionItemsRequest
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Personalized recommendation rows provided by the "For You" server plugin. Adds one row per section.
 *
 * The sections are loaded in the background so the rest of the home screen does not wait for them; when they arrive
 * the rows are inserted where this section sits in the home layout. Nothing is added when the plugin is not installed.
 */
class HomeFragmentForYouRow(
	private val scope: CoroutineScope,
	private val loadSections: suspend () -> List<ForYouSection>,
) : HomeFragmentRow, KoinComponent {
	private val userPreferences by inject<UserPreferences>()

	override fun addToRowsAdapter(context: Context, cardPresenter: CardPresenter, rowsAdapter: MutableObjectAdapter<Row>) {
		// Remember the row before this section so the For You rows can be inserted right after it later
		val previousRow = if (rowsAdapter.size() > 0) rowsAdapter.get(rowsAdapter.size() - 1) else null

		scope.launch {
			val sections = withContext(Dispatchers.IO) { loadSections() }
			if (sections.isEmpty()) return@launch

			var index = previousRow?.let { rowsAdapter.indexOf(it) + 1 } ?: 0
			for (row in createRows(context, cardPresenter, rowsAdapter, sections)) rowsAdapter.add(index++, row)
		}
	}

	private fun createRows(
		context: Context,
		cardPresenter: CardPresenter,
		rowsAdapter: MutableObjectAdapter<Row>,
		sections: List<ForYouSection>,
	): List<ListRow> {
		val preferParentThumb = userPreferences[UserPreferences.seriesThumbnailsEnabled]

		return sections.map { section ->
			val query = GetForYouSectionItemsRequest(
				sectionId = section.id,
				fields = ItemRepository.browseFields,
			)

			val rowAdapter = ItemRowAdapter(
				context,
				query,
				section.items,
				maxOf(section.totalRecordCount, section.items.size),
				CHUNK_SIZE,
				preferParentThumb,
				cardPresenter,
				rowsAdapter,
			)

			val row = ListRow(HeaderItem(section.title), rowAdapter)
			rowAdapter.setRow(row)
			rowAdapter.Retrieve()
			row
		}
	}

	companion object {
		// Amount of items loaded per page when scrolling through a row
		const val CHUNK_SIZE = 20

		// Name of the library view the server plugin adds for clients without For You rows
		private const val FOR_YOU_VIEW_NAME = "For You"

		fun isForYouView(item: BaseItemDto) = item.type == BaseItemKind.CHANNEL && item.name == FOR_YOU_VIEW_NAME
	}
}
