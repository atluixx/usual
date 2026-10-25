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

    private class FakeRepository : UsualRepository {
        var observer: (Account?) -> Unit = {}
        var profileObserver: ((ProfileState) -> Unit)? = null
        var signInCount = 0
        var signUpCount = 0
        var stoppedProfiles = 0
        var failNext = false
        var savedName: String? = null
        var gate: CompletableDeferred<Unit>? = null

        fun emit(account: Account?) = observer(account)

        override fun observeAccount(onChanged: (Account?) -> Unit): () -> Unit {
            observer = onChanged
            onChanged(null)
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
