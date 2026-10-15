package org.jellyfin.androidtv.ui.home

import android.content.Context
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Row
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
 */
class HomeFragmentForYouRow(
	private val sections: List<ForYouSection>,
) : HomeFragmentRow, KoinComponent {
	private val userPreferences by inject<UserPreferences>()

	override fun addToRowsAdapter(context: Context, cardPresenter: CardPresenter, rowsAdapter: MutableObjectAdapter<Row>) {
		val preferParentThumb = userPreferences[UserPreferences.seriesThumbnailsEnabled]

		for (section in sections) {
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
			rowsAdapter.add(row)
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
