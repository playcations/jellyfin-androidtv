package org.jellyfin.androidtv.ui.livetv

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.extensions.*
import org.jellyfin.sdk.api.client.extensions.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.preference.LiveTvPreferences
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.android.ext.android.inject
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

fun BaseItemDto.copyWithLastPlayedDate(
	lastPlayedDate: LocalDateTime,
) = copy(
	userData = userData?.copy(
		lastPlayedDate = lastPlayedDate,
	)
)

fun loadLiveTvChannels(fragment: Fragment, callback: (channels: Collection<BaseItemDto>?) -> Unit) {
	val liveTvPreferences by fragment.inject<LiveTvPreferences>()
	val api by fragment.inject<ApiClient>()

	fragment.lifecycleScope.launch {
		val users by fragment.inject<UserRepository>()
		val repository = ExtensionRepository(api, users)
		val viewer = repository.currentSession()
		val sortDatePlayed = liveTvPreferences[LiveTvPreferences.channelOrder] == ItemSortBy.DATE_PLAYED.serialName
		runCatching {
			requireNotNull(viewer)
			val scope = resolveGuideScope(fragment, repository, viewer)
			val channels = withContext(Dispatchers.IO) {
				if (scope == null) api.liveTvApi.getLiveTvChannels(
					addCurrentProgram = true,
					enableFavoriteSorting = liveTvPreferences[LiveTvPreferences.favsAtTop],
					sortBy = if (sortDatePlayed) setOf(ItemSortBy.DATE_PLAYED) else setOf(ItemSortBy.SORT_NAME),
					sortOrder = if (sortDatePlayed) SortOrder.DESCENDING else SortOrder.ASCENDING,
				).content.items else coroutineScope {
					// The standard channels list endpoint has no ID filter. Fetch only the explicitly authorized IDs.
					scope.channelIds.toList().chunked(8).flatMap { chunk -> chunk.map { id -> async {
						api.get<BaseItemDto>(pathTemplate = "/LiveTv/Channels/$id").content.also { require(it.id == id) }
					} }.awaitAll() }
				}
			}
			require(repository.currentSession() == viewer)
			(fragment as? LiveTvGuideFragment)?.guideSession?.authorizedIds = scope?.channelIds
			channels
		}.fold(onSuccess = { callback(it) }, onFailure = { callback(null) })
	}
}

/** Every channel and program request rechecks the viewer and bridge scope, including pagination/refresh. */
private suspend fun resolveGuideScope(fragment: Fragment, repository: ExtensionRepository, session: ExtensionSession): ExtensionGuideScope? {
	val args = fragment.arguments ?: return null
	if (!args.containsKey(ExtensionScreenFragment.ARG_SCOPE)) {
		require(!args.containsKey(ExtensionScreenFragment.ARG_EXTENSION) && !args.containsKey(ExtensionScreenFragment.ARG_USER)) { "Incomplete guide scope" }
		return null
	}
	require(matchesExtensionViewer(args, session)) { "Guide viewer changed" }
	return repository.guide(requireNotNull(args.getString(ExtensionScreenFragment.ARG_EXTENSION)), requireNotNull(args.getString(ExtensionScreenFragment.ARG_SCOPE)), session)
}

fun getPrograms(
	fragment: Fragment,
	channelIds: Array<UUID>,
	startTime: LocalDateTime,
	endTime: LocalDateTime,
	callback: (programs: Collection<BaseItemDto>?) -> Unit,
) {
	val api by fragment.inject<ApiClient>()
	val users by fragment.inject<UserRepository>()
	fragment.lifecycleScope.launch {
		val repository = ExtensionRepository(api, users)
		val session = repository.currentSession()
		runCatching {
			requireNotNull(session)
			require(channelIds.isNotEmpty()) // An empty list means all channels to Jellyfin; never send it.
			val scope = resolveGuideScope(fragment, repository, session)
			if (scope != null) {
				require(scope.channelIds == (fragment as LiveTvGuideFragment).guideSession.authorizedIds) { "Guide permissions changed" }
				require(channelIds.all { it in scope.channelIds })
			}
			val programs = withContext(Dispatchers.IO) {
				api.liveTvApi.getLiveTvPrograms(
					channelIds = channelIds.toList(), enableImages = false,
					sortBy = setOf(ItemSortBy.START_DATE), maxStartDate = endTime, minEndDate = startTime,
				).content.items.filter { it.channelId in channelIds && it.endDate != null }
			}
			require(repository.currentSession() == session)
			programs
		}.fold(onSuccess = { callback(it) }, onFailure = { callback(null) })
	}
}

fun getScheduleRows(
	fragment: Fragment,
	seriesTimerId: String?,
	callback: (timers: Map<LocalDate, List<BaseItemDto>>?) -> Unit,
) {
	val api by fragment.inject<ApiClient>()

	fragment.lifecycleScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				api.liveTvApi.getTimers(
					seriesTimerId = seriesTimerId,
				).content.items
			}
		}.fold(
			onSuccess = { timers ->
				val groupedTimers = timers
					.filterNot { it.startDate == null }
					.map { timer ->
						timer.programInfo ?: BaseItemDto(
							id = requireNotNull(timer.id?.toUUIDOrNull()),
							channelName = timer.channelName,
							name = timer.name.orEmpty(),
							type = BaseItemKind.PROGRAM,
							mediaType = MediaType.UNKNOWN,
							timerId = timer.id,
							seriesTimerId = timer.seriesTimerId,
							startDate = timer.startDate,
							endDate = timer.endDate,
						)
					}
					.map { it.copy(locationType = LocationType.VIRTUAL) }
					.groupBy { it.startDate!!.toLocalDate() }

				callback(groupedTimers)
			},
			onFailure = {
				callback(null)
			},
		)
	}
}

/** Legacy playback guide cache key. Scoped native guides use their own NativeGuideSession. */
fun currentGuideViewer(): ExtensionSession? {
	val api = org.koin.java.KoinJavaComponent.get<ApiClient>(ApiClient::class.java)
	val users = org.koin.java.KoinJavaComponent.get<UserRepository>(UserRepository::class.java)
	return ExtensionRepository(api, users).currentSession()
}
