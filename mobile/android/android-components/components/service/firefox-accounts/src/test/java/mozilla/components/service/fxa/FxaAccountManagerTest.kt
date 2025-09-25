/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.service.fxa

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import mozilla.appservices.fxaclient.FxaConfig
import mozilla.appservices.fxaclient.FxaServer
import mozilla.appservices.fxaclient.FxaState
import mozilla.components.concept.base.crash.CrashReporting
import mozilla.components.concept.sync.AccountEventsObserver
import mozilla.components.concept.sync.AccountObserver
import mozilla.components.concept.sync.AuthFlowError
import mozilla.components.concept.sync.AuthType
import mozilla.components.concept.sync.DeviceCapability
import mozilla.components.concept.sync.DeviceConfig
import mozilla.components.concept.sync.DeviceConstellation
import mozilla.components.concept.sync.DeviceType
import mozilla.components.concept.sync.FxAEntryPoint
import mozilla.components.concept.sync.StatePersistenceCallback
import mozilla.components.service.fxa.manager.FxaAccountManager
import mozilla.components.service.fxa.manager.SyncEnginesStorage
import mozilla.components.service.fxa.sync.SyncManager
import mozilla.components.service.fxa.sync.SyncReason
import mozilla.components.service.fxa.sync.SyncStatusObserver
import mozilla.components.support.base.observer.ObserverRegistry
import mozilla.components.support.test.any
import mozilla.components.support.test.argumentCaptor
import mozilla.components.support.test.eq
import mozilla.components.support.test.mock
import mozilla.components.support.test.robolectric.testContext
import mozilla.components.support.test.whenever
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import kotlin.coroutines.CoroutineContext

internal class TestableStorageWrapper(
    manager: FxaAccountManager,
    accountEventObserverRegistry: ObserverRegistry<AccountEventsObserver>,
    serverConfig: FxaConfig,
) : StorageWrapper(manager, accountEventObserverRegistry, serverConfig) {
    val account: FirefoxAccount = mock()
    val deviceConstellation: DeviceConstellation = mock()

    init {
        `when`(account.deviceConstellation()).thenReturn(deviceConstellation)
        `when`(account.getCurrentDeviceId()).thenReturn("testDeviceId")
    }

    override fun obtainAccount(): FirefoxAccount = account
}

// Same as the actual account manager, except we get to control how FirefoxAccountShaped instances
// are created. This is necessary because due to some build issues (native dependencies not available
// within the test environment) we can't use fxaclient supplied implementation of FirefoxAccountShaped.
// Instead, we express all of our account-related operations over an interface.
internal open class TestableFxaAccountManager(
    context: Context,
    config: FxaConfig,
    val storage: AccountStorage,
    capabilities: Set<DeviceCapability> = emptySet(),
    syncConfig: SyncConfig? = null,
    coroutineContext: CoroutineContext,
    crashReporter: CrashReporting? = null,
) : FxaAccountManager(context, config, DeviceConfig("test", DeviceType.UNKNOWN, capabilities), syncConfig, emptySet(), crashReporter, coroutineContext) {
    val testableStorageWrapper = TestableStorageWrapper(this, accountEventObserverRegistry, serverConfig)

    override var syncStatusObserverRegistry = ObserverRegistry<SyncStatusObserver>()

    override fun getStorageWrapper(): StorageWrapper {
        return testableStorageWrapper
    }

    override fun getAccountStorage(): AccountStorage {
        return storage
    }

    override fun createSyncManager(config: SyncConfig): SyncManager = mock()
}

@RunWith(AndroidJUnit4::class)
class FxaAccountManagerTest {
    @After
    fun cleanup() {
        SyncAuthInfoCache(testContext).clear()
        SyncEnginesStorage(testContext).clear()
    }

    val entryPoint: FxAEntryPoint = mock<FxAEntryPoint>().apply {
        whenever(entryName).thenReturn("home-menu")
    }

    @Test
    fun `account state persistence`() = runTest {
        val accountStorage: AccountStorage = mock()
        val fxaManager = TestableFxaAccountManager(
            context = testContext,
            config = FxaConfig(FxaServer.Release, "dummyId", "http://auth-url/redirect"),
            storage = accountStorage,
            capabilities = setOf(DeviceCapability.SEND_TAB),
            syncConfig = null,
            coroutineContext = this.coroutineContext,
        )
        val account = fxaManager.testableStorageWrapper.account

        // Before the FxaAccountManager is started, the persistence callback shouldn't be set
        verify(account, never()).registerPersistenceCallback(any())

        // After the FxaAccountManager is started, the persistence callback should be set
        fxaManager.start()
        val captor = argumentCaptor<StatePersistenceCallback>()
        verify(account).registerPersistenceCallback(captor.capture())

        // Assert that persistence callback is interacting with the storage layer.
        captor.value.persist("test")
        verify(accountStorage).write("test")
    }

    @Test
    fun `creating fresh account`() = runTest {
        val fxaManager = TestableFxaAccountManager(
            context = testContext,
            config = FxaConfig(FxaServer.Release, "dummyId", "http://auth-url/redirect"),
            storage = mock(),
            capabilities = setOf(DeviceCapability.SEND_TAB),
            syncConfig = null,
            coroutineContext = this.coroutineContext,
        )
        val account = fxaManager.testableStorageWrapper.account
        val accountObserver: AccountObserver = mock()
        fxaManager.register(accountObserver)

        verify(accountObserver, never()).onReady(any())
        verify(accountObserver, never()).onLoggedOut()
        verify(accountObserver, never()).onAuthenticated(any(), any())
        verify(accountObserver, never()).onProfileUpdated(any())
        verify(accountObserver, never()).onAuthenticationProblems()
        verify(accountObserver, never()).onFlowError(any())

        whenever(account.processEvent(any())).thenReturn(FxaState.Disconnected)
        fxaManager.start()
        verify(accountObserver, times(1)).onReady(any())
    }

    @Test
    fun `OAuth email login`() = runTest {
        val fxaManager = TestableFxaAccountManager(
            context = testContext,
            config = FxaConfig(FxaServer.Release, "dummyId", "http://auth-url/redirect"),
            storage = mock(),
            capabilities = setOf(DeviceCapability.SEND_TAB),
            syncConfig = null,
            coroutineContext = this.coroutineContext,
        )
        val account = fxaManager.testableStorageWrapper.account
        val accountObserver: AccountObserver = mock()
        fxaManager.register(accountObserver)

        whenever(account.processEvent(any())).thenReturn(FxaState.Disconnected)
        fxaManager.start()

        whenever(account.processEvent(any())).thenReturn(FxaState.Authenticating("https://test-oauth.example.com/"))
        fxaManager.beginAuthentication(entrypoint = entryPoint)

        whenever(account.processEvent(any())).thenReturn(FxaState.Connected)
        fxaManager.finishAuthentication(FxaAuthData(AuthType.Signin, "test-code", "test-auth-state"))

        verify(accountObserver, times(1)).onAuthenticated(any(), eq(AuthType.Signin))
    }

    @Test
    fun `OAuth pairing login`() = runTest {
        val fxaManager = TestableFxaAccountManager(
            context = testContext,
            config = FxaConfig(FxaServer.Release, "dummyId", "http://auth-url/redirect"),
            storage = mock(),
            capabilities = setOf(DeviceCapability.SEND_TAB),
            syncConfig = null,
            coroutineContext = this.coroutineContext,
        )
        val account = fxaManager.testableStorageWrapper.account
        val accountObserver: AccountObserver = mock()
        fxaManager.register(accountObserver)

        whenever(account.processEvent(any())).thenReturn(FxaState.Disconnected)
        fxaManager.start()

        whenever(account.processEvent(any())).thenReturn(FxaState.Authenticating("https://test-oauth.example.com/"))
        fxaManager.beginAuthentication(
            pairingUrl = "http://test-oauth/example.com/pairing",
            entrypoint = entryPoint,
        )

        whenever(account.processEvent(any())).thenReturn(FxaState.Connected)
        fxaManager.finishAuthentication(FxaAuthData(AuthType.Signin, "test-code", "test-auth-state"))

        verify(accountObserver, times(1)).onAuthenticated(any(), eq(AuthType.Signin))
    }

    @Test
    fun `Failure to begin OAuth login`() = runTest {
        val fxaManager = TestableFxaAccountManager(
            context = testContext,
            config = FxaConfig(FxaServer.Release, "dummyId", "http://auth-url/redirect"),
            storage = mock(),
            capabilities = setOf(DeviceCapability.SEND_TAB),
            syncConfig = null,
            coroutineContext = this.coroutineContext,
        )
        val account = fxaManager.testableStorageWrapper.account
        val accountObserver: AccountObserver = mock()
        fxaManager.register(accountObserver)

        whenever(account.processEvent(any())).thenReturn(FxaState.Disconnected)
        fxaManager.start()

        // Simulate the fxa client moving to the `Disconnected` state after `beginAuthentication()` is called.
        // This signals that the authentication failed at the very start.
        whenever(account.processEvent(any())).thenReturn(FxaState.Disconnected)
        fxaManager.beginAuthentication(entrypoint = entryPoint)

        verify(accountObserver, times(1)).onFlowError(AuthFlowError.FailedToBeginAuth)
    }

    @Test
    fun `Failure to finish OAuth login`() = runTest {
        val fxaManager = TestableFxaAccountManager(
            context = testContext,
            config = FxaConfig(FxaServer.Release, "dummyId", "http://auth-url/redirect"),
            storage = mock(),
            capabilities = setOf(DeviceCapability.SEND_TAB),
            syncConfig = null,
            coroutineContext = this.coroutineContext,
        )
        val account = fxaManager.testableStorageWrapper.account
        val accountObserver: AccountObserver = mock()
        fxaManager.register(accountObserver)

        whenever(account.processEvent(any())).thenReturn(FxaState.Disconnected)
        fxaManager.start()

        whenever(account.processEvent(any())).thenReturn(FxaState.Authenticating("https://test-oauth.example.com/"))
        fxaManager.beginAuthentication(entrypoint = entryPoint)

        // Simulate the fxa client moving to the `Disconnected` state after `finishAuthentication()` is called.
        // This signals that the authentication failed at the end.
        whenever(account.processEvent(any())).thenReturn(FxaState.Disconnected)
        fxaManager.finishAuthentication(FxaAuthData(AuthType.Signin, "test-code", "test-auth-state"))

        verify(accountObserver, times(1)).onFlowError(AuthFlowError.FailedToCompleteAuth)
    }

    @Test
    fun `restoring account`() = runTest {
        val fxaManager = TestableFxaAccountManager(
            context = testContext,
            config = FxaConfig(FxaServer.Release, "dummyId", "http://auth-url/redirect"),
            storage = mock(),
            capabilities = setOf(DeviceCapability.SEND_TAB),
            syncConfig = null,
            coroutineContext = this.coroutineContext,
        )
        val account = fxaManager.testableStorageWrapper.account
        val accountObserver: AccountObserver = mock()
        fxaManager.register(accountObserver)

        // Simulate the account moving to the `Connected` state immediately after starting up.  This
        // means it restored an existing auth state.
        whenever(account.processEvent(any())).thenReturn(FxaState.Connected)
        fxaManager.start()
        verify(accountObserver, times(1)).onReady(any())
        verify(accountObserver, times(1)).onAuthenticated(any(), eq(AuthType.Existing))
    }

    @Test
    fun `restoring account failure`() = runTest {
        val fxaManager = TestableFxaAccountManager(
            context = testContext,
            config = FxaConfig(FxaServer.Release, "dummyId", "http://auth-url/redirect"),
            storage = mock(),
            capabilities = setOf(DeviceCapability.SEND_TAB),
            syncConfig = null,
            coroutineContext = this.coroutineContext,
        )
        val account = fxaManager.testableStorageWrapper.account
        val accountObserver: AccountObserver = mock()
        fxaManager.register(accountObserver)

        // Simulate the account moving to the `AuthIssues` state immediately after starting up.  This
        // means it tried to restore an existing auth state and failed.
        whenever(account.processEvent(any())).thenReturn(FxaState.AuthIssues)
        fxaManager.start()
        verify(accountObserver, times(1)).onReady(any())
        verify(accountObserver, times(1)).onAuthenticationProblems()
    }

    @Test
    fun `logout`() = runTest {
        val fxaManager = TestableFxaAccountManager(
            context = testContext,
            config = FxaConfig(FxaServer.Release, "dummyId", "http://auth-url/redirect"),
            storage = mock(),
            capabilities = setOf(DeviceCapability.SEND_TAB),
            syncConfig = null,
            coroutineContext = this.coroutineContext,
        )
        val account = fxaManager.testableStorageWrapper.account
        val accountObserver: AccountObserver = mock()
        fxaManager.register(accountObserver)

        whenever(account.processEvent(any())).thenReturn(FxaState.Disconnected)
        fxaManager.start()

        whenever(account.processEvent(any())).thenReturn(FxaState.Authenticating("https://test-oauth.example.com/"))
        fxaManager.beginAuthentication(entrypoint = entryPoint)

        whenever(account.processEvent(any())).thenReturn(FxaState.Connected)
        fxaManager.finishAuthentication(FxaAuthData(AuthType.Signin, "test-code", "test-auth-state"))

        whenever(account.processEvent(any())).thenReturn(FxaState.Disconnected)
        fxaManager.logout()
        verify(accountObserver, times(1)).onLoggedOut()
    }

    @Test
    fun `Auth issues`() = runTest {
        val fxaManager = TestableFxaAccountManager(
            context = testContext,
            config = FxaConfig(FxaServer.Release, "dummyId", "http://auth-url/redirect"),
            storage = mock(),
            capabilities = setOf(DeviceCapability.SEND_TAB),
            syncConfig = null,
            coroutineContext = this.coroutineContext,
        )
        val account = fxaManager.testableStorageWrapper.account
        val accountObserver: AccountObserver = mock()
        fxaManager.register(accountObserver)

        whenever(account.processEvent(any())).thenReturn(FxaState.Disconnected)
        fxaManager.start()

        whenever(account.processEvent(any())).thenReturn(FxaState.Authenticating("https://test-oauth.example.com/"))
        fxaManager.beginAuthentication(entrypoint = entryPoint)

        whenever(account.processEvent(any())).thenReturn(FxaState.Connected)
        fxaManager.finishAuthentication(FxaAuthData(AuthType.Signin, "test-code", "test-auth-state"))

        // Simulate an authentication error during operation that we don't recover from
        whenever(account.processEvent(any())).thenReturn(FxaState.AuthIssues)
        fxaManager.encounteredAuthError("fake operation", 1)
        verify(accountObserver, times(1)).onAuthenticationProblems()
    }

    @Test
    fun `Recovery from auth issues`() = runTest {
        val fxaManager = TestableFxaAccountManager(
            context = testContext,
            config = FxaConfig(FxaServer.Release, "dummyId", "http://auth-url/redirect"),
            storage = mock(),
            capabilities = setOf(DeviceCapability.SEND_TAB),
            syncConfig = null,
            coroutineContext = this.coroutineContext,
        )
        val account = fxaManager.testableStorageWrapper.account
        val accountObserver: AccountObserver = mock()
        fxaManager.register(accountObserver)

        whenever(account.processEvent(any())).thenReturn(FxaState.Disconnected)
        fxaManager.start()

        whenever(account.processEvent(any())).thenReturn(FxaState.Authenticating("https://test-oauth.example.com/"))
        fxaManager.beginAuthentication(entrypoint = entryPoint)

        whenever(account.processEvent(any())).thenReturn(FxaState.Connected)
        fxaManager.finishAuthentication(FxaAuthData(AuthType.Signin, "test-code", "test-auth-state"))

        // Simulate an authentication error during operation that we do recover from
        whenever(account.processEvent(any())).thenReturn(FxaState.Connected)
        fxaManager.encounteredAuthError("fake operation", 1)
        verify(accountObserver, times(1)).onAuthenticated(any(), eq(AuthType.Recovered))
    }

    @Test
    fun `Logout after auth issues`() = runTest {
        val fxaManager = TestableFxaAccountManager(
            context = testContext,
            config = FxaConfig(FxaServer.Release, "dummyId", "http://auth-url/redirect"),
            storage = mock(),
            capabilities = setOf(DeviceCapability.SEND_TAB),
            syncConfig = null,
            coroutineContext = this.coroutineContext,
        )
        val account = fxaManager.testableStorageWrapper.account
        val accountObserver: AccountObserver = mock()
        fxaManager.register(accountObserver)

        whenever(account.processEvent(any())).thenReturn(FxaState.Disconnected)
        fxaManager.start()

        whenever(account.processEvent(any())).thenReturn(FxaState.Authenticating("https://test-oauth.example.com/"))
        fxaManager.beginAuthentication(entrypoint = entryPoint)

        whenever(account.processEvent(any())).thenReturn(FxaState.Connected)
        fxaManager.finishAuthentication(FxaAuthData(AuthType.Signin, "test-code", "test-auth-state"))

        // Simulate an authentication error during operation that we don't recover from
        whenever(account.processEvent(any())).thenReturn(FxaState.AuthIssues)
        fxaManager.encounteredAuthError("fake operation", 1)
        whenever(account.processEvent(any())).thenReturn(FxaState.Disconnected)
        fxaManager.logout()
        verify(accountObserver, times(1)).onAuthenticationProblems()
        verify(accountObserver, times(1)).onLoggedOut()
    }

    @Test
    fun `accounts to sync integration`() {
        val syncManager: SyncManager = mock()
        val integration = FxaAccountManager.AccountsToSyncIntegration(syncManager)

        // onAuthenticated - mapping of AuthType to SyncReason
        integration.onAuthenticated(mock(), AuthType.Signin)
        verify(syncManager, times(1)).start()
        verify(syncManager, times(1)).now(eq(SyncReason.FirstSync), anyBoolean(), eq(listOf()))
        integration.onAuthenticated(mock(), AuthType.Signup)
        verify(syncManager, times(2)).start()
        verify(syncManager, times(2)).now(eq(SyncReason.FirstSync), anyBoolean(), eq(listOf()))
        integration.onAuthenticated(mock(), AuthType.Pairing)
        verify(syncManager, times(3)).start()
        verify(syncManager, times(3)).now(eq(SyncReason.FirstSync), anyBoolean(), eq(listOf()))
        integration.onAuthenticated(mock(), AuthType.MigratedReuse)
        verify(syncManager, times(4)).start()
        verify(syncManager, times(4)).now(eq(SyncReason.FirstSync), anyBoolean(), eq(listOf()))
        integration.onAuthenticated(mock(), AuthType.OtherExternal("test"))
        verify(syncManager, times(5)).start()
        verify(syncManager, times(5)).now(eq(SyncReason.FirstSync), anyBoolean(), eq(listOf()))
        integration.onAuthenticated(mock(), AuthType.Existing)
        verify(syncManager, times(6)).start()
        verify(syncManager, times(1)).now(eq(SyncReason.Startup), anyBoolean(), eq(listOf()))
        integration.onAuthenticated(mock(), AuthType.Recovered)
        verify(syncManager, times(7)).start()
        verify(syncManager, times(2)).now(eq(SyncReason.Startup), anyBoolean(), eq(listOf()))
        verifyNoMoreInteractions(syncManager)

        // onProfileUpdated - no-op
        integration.onProfileUpdated(mock())
        verifyNoMoreInteractions(syncManager)

        // onAuthenticationProblems
        integration.onAuthenticationProblems()
        verify(syncManager).stop()
        verifyNoMoreInteractions(syncManager)

        // onLoggedOut
        integration.onLoggedOut()
        verify(syncManager, times(2)).stop()
        verifyNoMoreInteractions(syncManager)
    }

    @Test
    fun `GIVEN a sync observer WHEN registering it THEN add it to the sync observer registry`() {
        val fxaManager = TestableFxaAccountManager(
            context = testContext,
            config = mock(),
            storage = mock(),
            capabilities = setOf(DeviceCapability.SEND_TAB),
            syncConfig = null,
            coroutineContext = mock(),
        )
        fxaManager.syncStatusObserverRegistry = mock()
        val observer: SyncStatusObserver = mock()
        val lifecycleOwner: LifecycleOwner = mock()

        fxaManager.registerForSyncEvents(observer, lifecycleOwner, false)

        verify(fxaManager.syncStatusObserverRegistry).register(observer, lifecycleOwner, false)
        verifyNoMoreInteractions(fxaManager.syncStatusObserverRegistry)
    }

    @Test
    fun `GIVEN a sync observer WHEN unregistering it THEN remove it from the sync observer registry`() {
        val fxaManager = TestableFxaAccountManager(
            context = testContext,
            config = mock(),
            storage = mock(),
            capabilities = setOf(DeviceCapability.SEND_TAB),
            syncConfig = null,
            coroutineContext = mock(),
        )
        fxaManager.syncStatusObserverRegistry = mock()
        val observer: SyncStatusObserver = mock()

        fxaManager.unregisterForSyncEvents(observer)

        verify(fxaManager.syncStatusObserverRegistry).unregister(observer)
        verifyNoMoreInteractions(fxaManager.syncStatusObserverRegistry)
    }
}
