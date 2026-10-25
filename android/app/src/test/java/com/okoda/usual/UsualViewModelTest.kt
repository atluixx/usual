package com.okoda.usual

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UsualViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `guest entry uses no email or verification and duplicate taps reuse the operation`() = runTest(dispatcher) {
        val repository = FakeRepository()
        repository.gate = CompletableDeferred()
        val model = UsualViewModel(repository)
        model.startGuest()
        model.startGuest()
        testScheduler.runCurrent()
        assertEquals(1, repository.guestCount)
        repository.gate?.complete(Unit)
        advanceUntilIdle()
        assertTrue(model.state.value.account!!.isGuest)
        assertEquals(0, repository.signInCount)
        assertEquals(0, repository.signUpCount)
    }

    @Test
    fun `guest connection failure can retry without requesting credentials`() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = UsualViewModel(repository)
        repository.failNext = true
        model.startGuest()
        advanceUntilIdle()
        assertNull(model.state.value.account)
        assertFalse(model.state.value.busy)
        model.startGuest()
        advanceUntilIdle()
        assertTrue(model.state.value.account!!.isGuest)
        model.saveProfile("Guest")
        advanceUntilIdle()
        assertEquals("Guest", repository.savedName)
    }

    @Test
    fun `Google linking keeps guest UID profile and subscription`() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = UsualViewModel(repository)
        repository.emit(Account("guest", null, false, isGuest = true))
        repository.profileObserver?.invoke(ProfileState.Ready(Profile("Guest's name")))
        model.linkGoogle { "test-token" }
        advanceUntilIdle()
        assertEquals("guest", model.state.value.account?.uid)
        assertTrue(model.state.value.account!!.googleLinked)
        assertFalse(model.state.value.account!!.isGuest)
        assertEquals(ProfileState.Ready(Profile("Guest's name")), model.state.value.profile)
        assertEquals(0, repository.stoppedProfiles)
        assertEquals(0, repository.signInGoogleCount)
    }

    @Test
    fun `Google collision or picker cancellation keeps the existing guest and allows retry`() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = UsualViewModel(repository)
        val guest = Account("guest", null, false, isGuest = true)
        repository.emit(guest)
        repository.profileObserver?.invoke(ProfileState.Ready(Profile("Private")))
        model.linkGoogle { throw UserFacingException("Cancelled") }
        advanceUntilIdle()
        assertEquals(0, repository.linkCount)
        repository.failNext = true
        model.linkGoogle { "test-token" }
        advanceUntilIdle()
        assertEquals(guest, model.state.value.account)
        assertEquals(ProfileState.Ready(Profile("Private")), model.state.value.profile)
        assertFalse(model.state.value.busy)
        model.linkGoogle { "test-token" }
        advanceUntilIdle()
        assertTrue(model.state.value.account!!.googleLinked)
    }

    @Test
    fun `restored guest needs no new sign in and cannot accidentally sign out`() = runTest(dispatcher) {
        val guest = Account("guest", null, false, isGuest = true)
        val repository = FakeRepository(initialAccount = guest)
        val model = UsualViewModel(repository)
        advanceUntilIdle()
        assertEquals(guest, model.state.value.account)
        assertEquals(0, repository.guestCount)
        model.signOut()
        assertEquals(guest, model.state.value.account)
        assertNotNull(model.state.value.notice)
    }

    @Test
    fun `late Google picker result cannot link a different current user`() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = UsualViewModel(repository)
        repository.emit(Account("guest", null, false, isGuest = true))
        val token = CompletableDeferred<String>()
        model.linkGoogle { token.await() }
        testScheduler.runCurrent()
        repository.emit(Account("different", null, false, isGuest = true))
        token.complete("test-token")
        advanceUntilIdle()
        assertEquals(0, repository.linkCount)
        assertEquals("different", model.state.value.account?.uid)
    }

    @Test
    fun `Google sign in never replaces an active guest`() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = UsualViewModel(repository)
        repository.emit(Account("guest", null, false, isGuest = true))
        model.signInGoogle { error("Picker should not open") }
        advanceUntilIdle()
        assertEquals(0, repository.signInGoogleCount)
        assertEquals("guest", model.state.value.account?.uid)
    }

    @Test
    fun `invalid input never submits credentials`() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = UsualViewModel(repository)
        model.signUp("invalid", "short")
        model.signIn("", "")
        advanceUntilIdle()
        assertEquals(0, repository.signInCount)
        assertEquals(0, repository.signUpCount)
        assertNotNull(model.state.value.notice)
    }

    @Test
    fun `failure clears loading and permits a successful retry`() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = UsualViewModel(repository)
        repository.failNext = true
        model.signIn("alice@example.test", "password")
        advanceUntilIdle()
        assertFalse(model.state.value.busy)
        assertNotNull(model.state.value.notice)
        model.signIn("alice@example.test", "password")
        advanceUntilIdle()
        assertEquals("alice", model.state.value.account?.uid)
        assertEquals(2, repository.signInCount)
    }

    @Test
    fun `duplicate taps do not submit overlapping sign in attempts`() = runTest(dispatcher) {
        val repository = FakeRepository()
        repository.gate = CompletableDeferred()
        val model = UsualViewModel(repository)
        model.signIn("alice@example.test", "password")
        model.signIn("alice@example.test", "password")
        testScheduler.runCurrent()
        assertTrue(model.state.value.busy)
        assertEquals(1, repository.signInCount)
        repository.gate?.complete(Unit)
        advanceUntilIdle()
        assertFalse(model.state.value.busy)
    }

    @Test
    fun `sign out clears profile and ignores a late previous user snapshot`() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = UsualViewModel(repository)
        repository.emit(Account("alice", "alice@example.test", false))
        val stale = repository.profileObserver
        stale?.invoke(ProfileState.Ready(Profile("Private name")))
        model.signOut()
        stale?.invoke(ProfileState.Ready(Profile("Old private name")))
        assertNull(model.state.value.account)
        assertEquals(ProfileState.Missing, model.state.value.profile)
        assertEquals(1, repository.stoppedProfiles)
    }

    @Test
    fun `profile setup retries independently of account creation`() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = UsualViewModel(repository)
        model.signUp("alice@example.test", "password")
        advanceUntilIdle()
        repository.failNext = true
        model.saveProfile("  Alice  ")
        advanceUntilIdle()
        assertNotNull(model.state.value.account)
        model.saveProfile("  Alice  ")
        advanceUntilIdle()
        assertEquals("Alice", repository.savedName)
        assertEquals(1, repository.signUpCount)
    }

    @Test
    fun `oversized profile names never reach persistence`() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = UsualViewModel(repository)
        repository.emit(Account("alice", null, false))
        model.saveProfile("x".repeat(81))
        advanceUntilIdle()
        assertNull(repository.savedName)
        assertNotNull(model.state.value.notice)
    }

    private class FakeRepository(private val initialAccount: Account? = null) : UsualRepository {
        var observer: (Account?) -> Unit = {}
        private var account: Account? = initialAccount
        var profileObserver: ((ProfileState) -> Unit)? = null
        var signInCount = 0
        var signUpCount = 0
        var guestCount = 0
        var linkCount = 0
        var signInGoogleCount = 0
        var stoppedProfiles = 0
        var failNext = false
        var savedName: String? = null
        var gate: CompletableDeferred<Unit>? = null

        fun emit(value: Account?) {
            account = value
            observer(value)
        }

        override fun observeAccount(onChanged: (Account?) -> Unit): () -> Unit {
            observer = onChanged
            onChanged(initialAccount)
            return {}
        }

        override fun observeProfile(uid: String, onChanged: (ProfileState) -> Unit): () -> Unit {
            profileObserver = onChanged
            return { stoppedProfiles++ }
        }

        override suspend fun signUp(email: String, password: String) {
            signUpCount++
            failIfRequested()
            emit(Account("alice", email, false))
        }

        override suspend fun startGuest() {
            guestCount++
            gate?.await()
            failIfRequested()
            if (account == null) emit(Account("guest", null, false, isGuest = true))
        }

        override suspend fun linkGoogle(uid: String, idToken: String) {
            linkCount++
            failIfRequested()
            check(account?.uid == uid)
            emit(account!!.copy(isGuest = false, googleLinked = true))
        }

        override suspend fun signInGoogle(idToken: String) {
            signInGoogleCount++
            failIfRequested()
            emit(Account("restored", "restored@example.test", true, googleLinked = true))
        }

        override suspend fun signIn(email: String, password: String) {
            signInCount++
            gate?.await()
            failIfRequested()
            emit(Account("alice", email, false))
        }

        override suspend fun saveProfile(uid: String, displayName: String) {
            failIfRequested()
            savedName = displayName
        }

        private fun failIfRequested() {
            if (failNext) {
                failNext = false
                throw UserFacingException("Connection failed. Retry.")
            }
        }

        override fun signOut() = emit(null)
        override suspend fun resetPassword(email: String) = Unit
        override suspend fun sendVerification() = Unit
        override suspend fun refreshAccount() = Unit
        override suspend fun refreshProfile(uid: String) = Unit
    }
}
