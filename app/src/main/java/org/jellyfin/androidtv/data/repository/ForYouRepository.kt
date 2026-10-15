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
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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

	/**
	 * Check if the server supports the "Recommended" library sort. The result is cached per server.
	 */
	suspend fun isRecommendedSortAvailable(): Boolean

	/**
	 * Get a page of library items in recommended order (unwatched recommendations first, watched items last).
	 */
	@Suppress("LongParameterList")
	suspend fun getRecommendedItems(
		parentId: UUID?,
		includeItemTypes: Collection<BaseItemKind>?,
		startIndex: Int,
		limit: Int,
		fields: Collection<ItemFields>? = null,
		isPlayed: Boolean? = null,
		isFavorite: Boolean? = null,
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

	private val recommendedSortAvailability = ConcurrentHashMap<String, Boolean>()

	override suspend fun isRecommendedSortAvailable(): Boolean {
		val baseUrl = api.baseUrl ?: return false
		recommendedSortAvailability[baseUrl]?.let { return it }

		return withContext(Dispatchers.IO) {
			try {
				api.get<BaseItemDtoQueryResult>(
					pathTemplate = "/ForYou/Recommended",
					queryParameters = mapOf("limit" to 0),
				)
				recommendedSortAvailability[baseUrl] = true
				true
			} catch (err: InvalidStatusException) {
				if (err.status == HTTP_NOT_FOUND) Timber.d("For You recommended sort not available on server")
				else Timber.w(err, "Failed to check For You recommended sort availability")
				recommendedSortAvailability[baseUrl] = false
				false
			} catch (err: ApiClientException) {
				// Network errors are not cached so the check is retried later
				Timber.w(err, "Failed to check For You recommended sort availability")
				false
			}
		}
	}

	override suspend fun getRecommendedItems(
		parentId: UUID?,
		includeItemTypes: Collection<BaseItemKind>?,
		startIndex: Int,
		limit: Int,
		fields: Collection<ItemFields>?,
		isPlayed: Boolean?,
		isFavorite: Boolean?,
	): BaseItemDtoQueryResult = withContext(Dispatchers.IO) {
		api.get<BaseItemDtoQueryResult>(
			pathTemplate = "/ForYou/Recommended",
			queryParameters = buildMap {
				put("parentId", parentId)
				put("includeItemTypes", includeItemTypes)
				put("startIndex", startIndex)
				put("limit", limit)
				put("fields", fields)
				put("isPlayed", isPlayed)
				put("isFavorite", isFavorite)
				put("enableImages", true)
			},
		).content
	}

	private companion object {
		private const val HTTP_NOT_FOUND = 404
	}
}
