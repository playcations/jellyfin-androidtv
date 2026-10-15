package org.jellyfin.androidtv.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.model.api.BaseItemDto

/**
 * Response of the "For You" server plugin `GET /ForYou/Sections` endpoint.
 */
@Serializable
data class ForYouSectionsResponse(
	@SerialName("Sections")
	val sections: List<ForYouSection> = emptyList(),
)

/**
 * A single personalized recommendation section, rendered as a row on the home screen.
 */
@Serializable
data class ForYouSection(
	@SerialName("Id")
	val id: String,
	@SerialName("Title")
	val title: String,
	@SerialName("Kind")
	val kind: String? = null,
	@SerialName("SourceItemId")
	val sourceItemId: String? = null,
	@SerialName("TotalRecordCount")
	val totalRecordCount: Int = 0,
	@SerialName("Items")
	val items: List<BaseItemDto> = emptyList(),
)
