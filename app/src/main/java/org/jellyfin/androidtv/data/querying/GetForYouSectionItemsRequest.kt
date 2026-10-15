package org.jellyfin.androidtv.data.querying

import org.jellyfin.sdk.model.api.ItemFields

data class GetForYouSectionItemsRequest(
	val sectionId: String,
	val fields: Collection<ItemFields>? = null,
)
