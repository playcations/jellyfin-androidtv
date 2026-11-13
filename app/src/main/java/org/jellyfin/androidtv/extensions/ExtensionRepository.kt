package org.jellyfin.androidtv.extensions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.sdk.api.client.ApiClient
import java.net.HttpURLConnection
import java.net.URI

/** Never serialized or logged. Token participates in equality so reauthentication invalidates in-flight work. */
class ExtensionSession(val server: String, val user: String, val token: String) {
	override fun equals(other: Any?) = other is ExtensionSession && server == other.server && user == other.user && token == other.token
	override fun hashCode() = 31 * server.hashCode() + user.hashCode()
	override fun toString() = "ExtensionSession(redacted)"
}
fun interface ExtensionTransport { suspend fun get(session: ExtensionSession, path: String): String }
class ServerExtensionTransport : ExtensionTransport {
	override suspend fun get(session: ExtensionSession, path: String): String = withContext(Dispatchers.IO) {
		require((path == ExtensionRepository.ROOT || path.startsWith(ExtensionRepository.ROOT + "/")) && !path.contains(".."))
		val base = URI(session.server.trimEnd('/') + "/")
		require(base.scheme in listOf("http", "https") && base.host != null && base.userInfo == null)
		val url = URI(base.toString() + path.removePrefix("/"))
		require(url.host == base.host && url.port == base.port && url.scheme == base.scheme)
		val c = url.toURL().openConnection() as HttpURLConnection
		try {
			c.instanceFollowRedirects = false
			c.connectTimeout = 8000
			c.readTimeout = 8000
			c.setRequestProperty("Authorization", extensionAuthorization(session.token))
			c.setRequestProperty("Accept", "application/json")
			require(c.responseCode == 200) { "Extension unavailable (HTTP ${c.responseCode})" }
			val bytes = c.inputStream.use { input ->
				val result = java.io.ByteArrayOutputStream()
				val buffer = ByteArray(8192)
				while (true) {
					val n = input.read(buffer)
					if (n == -1) break
					require(result.size() + n <= 4 * 1024 * 1024) { "Extension response too large" }
					result.write(buffer, 0, n)
				}
				result.toByteArray()
			}
			bytes.toString(Charsets.UTF_8)
		} finally { c.disconnect() }
	}
}

/** Deliberately no discovery/page/scope cache: every operation rechecks the active viewer and provider. */
class ExtensionRepository(val currentSession: () -> ExtensionSession?, private val transport: ExtensionTransport = ServerExtensionTransport()) {
	constructor(api: ApiClient, users: UserRepository) : this({
		val server = api.baseUrl
		val token = api.accessToken
		val user = users.currentUser.value
		if (server == null || token == null || user == null) null else ExtensionSession(server, user.id.toString(), token)
	})
	private suspend fun get(session: ExtensionSession, path: String): String {
		require(currentSession() == session) { "Viewer changed" }
		val body = transport.get(session, path)
		require(currentSession() == session) { "Viewer changed" }
		return body
	}
	suspend fun discover(session: ExtensionSession = requireNotNull(currentSession())) = ExtensionProtocol.discovery(get(session, ROOT))
	private suspend fun requireProvider(session: ExtensionSession, extension: String) {
		ExtensionProtocol.identifier(extension)
		require(discover(session).any { it.id == extension }) { "Extension removed or access revoked" }
	}
	suspend fun screen(extension: String, screen: String, start: Int = 0, limit: Int = ExtensionProtocol.PAGE_SIZE, session: ExtensionSession = requireNotNull(currentSession())): ExtensionPage {
		ExtensionProtocol.identifier(screen)
		require(start >= 0 && limit in 1..100)
		requireProvider(session, extension)
		return ExtensionProtocol.page(get(session, "$ROOT/extensions/$extension/screens/$screen?startIndex=$start&limit=$limit"), extension, screen, start, limit)
	}
	suspend fun guide(extension: String, scope: String, session: ExtensionSession = requireNotNull(currentSession())): ExtensionGuideScope {
		ExtensionProtocol.identifier(scope)
		requireProvider(session, extension)
		return ExtensionProtocol.scope(get(session, "$ROOT/extensions/$extension/guide-scopes/$scope"), scope)
	}
	companion object { const val ROOT = "/ClientExtensions/v1" }
}

/** Jellyfin 12 uses the standard MediaBrowser header; legacy token-only headers may be disabled. */
internal fun extensionAuthorization(token: String): String {
	require(token.isNotEmpty() && token.length <= 4096 && token.none { it == '\r' || it == '\n' || it == '\"' || it == '\\' })
	return "MediaBrowser Client=\"Familyfin Native\", Device=\"Android TV\", DeviceId=\"familyfin-native-extensions\", Version=\"0.1.0\", Token=\"$token\""
}
