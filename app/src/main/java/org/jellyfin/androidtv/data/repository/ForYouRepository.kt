package org.jellyfin.androidtv.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.data.model.ForYouSection
import org.jellyfin.androidtv.data.model.ForYouSectionsResponse
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.get
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.ItemFields
import timber.log.Timber

/**
 * Client for the "For You" server plugin that provides personalized recommendation sections.
 */
interface ForYouRepository {
	/**
	 * Get all recommendation sections for the current user. Returns an empty list when the plugin is not installed or the request
	 * failed.
	 */
	suspend fun getSections(
		sectionLimit: Int? = null,
		itemLimit: Int? = null,
		fields: Collection<ItemFields>? = null,
	): List<ForYouSection>

	/**
	 * Get a page of items for a single recommendation section.
	 */
	suspend fun getSectionItems(
		sectionId: String,
		startIndex: Int,
		limit: Int,
		fields: Collection<ItemFields>? = null,
	): BaseItemDtoQueryResult
}

class ForYouRepositoryImpl(
	private val api: ApiClient,
) : ForYouRepository {
	override suspend fun getSections(
		sectionLimit: Int?,
		itemLimit: Int?,
		fields: Collection<ItemFields>?,
	): List<ForYouSection> = withContext(Dispatchers.IO) {
		try {
			api.get<ForYouSectionsResponse>(
				pathTemplate = "/ForYou/Sections",
				queryParameters = buildMap {
					put("sectionLimit", sectionLimit)
					put("itemLimit", itemLimit)
					put("fields", fields)
					put("enableImages", true)
				},
			).content.sections.filter { it.items.isNotEmpty() }
		} catch (err: InvalidStatusException) {
			if (err.status == HTTP_NOT_FOUND) Timber.d("For You plugin not available on server")
			else Timber.w(err, "Failed to retrieve For You sections")
			emptyList()
		} catch (err: ApiClientException) {
			Timber.w(err, "Failed to retrieve For You sections")
			emptyList()
		}
	}

	override suspend fun getSectionItems(
		sectionId: String,
		startIndex: Int,
		limit: Int,
		fields: Collection<ItemFields>?,
	): BaseItemDtoQueryResult = withContext(Dispatchers.IO) {
		api.get<BaseItemDtoQueryResult>(
			pathTemplate = "/ForYou/Sections/{sectionId}/Items",
			pathParameters = mapOf("sectionId" to sectionId),
			queryParameters = buildMap {
				put("startIndex", startIndex)
				put("limit", limit)
				put("fields", fields)
			},
		).content
	}

	private companion object {
		private const val HTTP_NOT_FOUND = 404
	}
}
