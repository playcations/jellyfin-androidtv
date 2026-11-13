package org.jellyfin.androidtv.extensions

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

class ExtensionLifecycleTests : FunSpec({
	val one = UUID.fromString("00000000-0000-0000-0000-000000000001")
	val two = UUID.fromString("00000000-0000-0000-0000-000000000002")
	test("filter reload cannot silently adopt changed guide permissions") {
		val state = GuideState()
		state.authorize(state.generation, setOf(one)) shouldBe true
		val reload = state.reload()
		shouldThrowAny { state.authorize(reload, setOf(two)) }
		state.authorizedIds shouldBe setOf(one)
		state.channels shouldBe emptyList()
	}
	test("valid scope can be confirmed while slow channel detail loading is still pending") {
		val state = GuideState()
		val load = state.reload()
		state.authorize(load, setOf(one)) shouldBe true
		state.channels shouldBe emptyList()
		state.authorize(load, setOf(one)) shouldBe true
		state.channels(load, listOf(BaseItemDto(id = one, type = BaseItemKind.TV_CHANNEL)))
		state.channels.single().id shouldBe one
	}
	test("a validator may establish the initial scope before the first channel load completes") {
		val state = GuideState()
		val load = state.reload()
		state.authorize(load, setOf(one)) shouldBe true
		state.authorize(load, setOf(one)) shouldBe true
		shouldThrowAny { state.authorize(load, setOf(two)) }
	}
	test("old load or validator responses cannot establish a scope after clear or newer reload") {
		val state = GuideState()
		val old = state.reload()
		state.clear()
		state.authorize(old, setOf(one)) shouldBe false
		state.authorizedIds shouldBe null
		val current = state.reload()
		state.authorize(current, setOf(two)) shouldBe true
		state.authorize(old, setOf(one)) shouldBe false
		state.authorizedIds shouldBe setOf(two)
	}
	test("an unavailable guide cannot be reopened by another filter or resume reload") {
		val state = GuideState()
		state.authorize(state.generation, setOf(one)) shouldBe true
		state.invalidate()
		val reload = state.reload()
		state.authorize(reload, setOf(two)) shouldBe false
		state.unavailable shouldBe true
		state.authorizedIds shouldBe null
	}
	test("revoked screens reject late page results even from non-cancellable work") {
		val work = ExtensionWorkState()
		val generation = work.resume()
		val release = CompletableDeferred<Unit>()
		var published = false
		coroutineScope {
			val pending = async(start = CoroutineStart.UNDISPATCHED) {
				withContext(NonCancellable) { release.await() }
				work.requireCurrent(generation)
				published = true
			}
			work.invalidate()
			release.complete(Unit)
			shouldThrowAny { pending.await() }
		}
		published shouldBe false
	}
	test("resuming a screen cannot reactivate work from its previous visible lifetime") {
		val work = ExtensionWorkState()
		val old = work.resume()
		work.invalidate()
		val resumed = work.resume()
		work.accepts(old) shouldBe false
		work.accepts(resumed) shouldBe true
	}
})
