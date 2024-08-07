// Copyright 2017, Google LLC, Christopher Banes and the Tivi project contributors
// SPDX-License-Identifier: Apache-2.0

package app.tivi.data.traktauth

import app.tivi.data.traktauth.store.AuthStore
import app.tivi.inject.ApplicationCoroutineScope
import app.tivi.inject.ApplicationScope
import app.tivi.util.AppCoroutineDispatchers
import app.tivi.util.Logger
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import me.tatarka.inject.annotations.Inject

@ApplicationScope
@Inject
class TraktAuthRepository(
  private val scope: ApplicationCoroutineScope,
  private val dispatchers: AppCoroutineDispatchers,
  private val authStore: AuthStore,
  private val loginAction: Lazy<TraktLoginAction>,
  private val refreshTokenAction: Lazy<TraktRefreshTokenAction>,
  private val logger: Logger,
) {
  private val _state = MutableStateFlow(TraktAuthState.LOGGED_OUT)
  val state: StateFlow<TraktAuthState> get() = _state.asStateFlow()

  private var lastAuthState: AuthState? = null
  private var lastAuthStateExpiry: Instant = Instant.DISTANT_PAST

  init {
    // Read the auth state from the AuthStore
    scope.launch {
      val state = getAuthState() ?: AuthState.Empty
      updateAuthState(authState = state, persist = false)
    }
  }

  suspend fun getAuthState(): AuthState? {
    val state = lastAuthState
    if (state != null && Clock.System.now() < lastAuthStateExpiry) {
      logger.d { "[TraktAuthRepository] getAuthState. Using cached tokens: $state" }
      return state
    }

    logger.d { "[TraktAuthRepository] getAuthState. Retrieving tokens from AuthStore" }
    return withContext(dispatchers.io) { authStore.get() }
      ?.also { cacheAuthState(it) }
  }

  suspend fun login(): AuthState? {
    logger.d { "[TraktAuthRepository] login()" }
    return loginAction.value()
      .also { updateAuthState(authState = it ?: AuthState.Empty) }
      .also {
        logger.d { "[TraktAuthRepository] login finished. Result: $it" }
      }
  }

  suspend fun refreshTokens(): AuthState? {
    logger.d { "[TraktAuthRepository] refreshTokens" }
    return getAuthState()
      ?.let { currentState ->
        logger.d { "[TraktAuthRepository] Calling refreshTokenAction with $currentState" }
        refreshTokenAction.value.invoke(currentState)
      }
      .also { updateAuthState(authState = it ?: AuthState.Empty) }
      .also {
        logger.d { "[TraktAuthRepository] refreshTokens finished. Result: $it" }
      }
  }

  suspend fun logout() {
    updateAuthState(authState = AuthState.Empty)
  }

  private fun cacheAuthState(authState: AuthState) {
    if (authState.isAuthorized) {
      lastAuthState = authState
      lastAuthStateExpiry = Clock.System.now() + 1.hours
    } else {
      lastAuthState = null
      lastAuthStateExpiry = Instant.DISTANT_PAST
    }
  }

  private suspend fun updateAuthState(authState: AuthState, persist: Boolean = true) {
    logger.d { "[TraktAuthRepository] updateAuthState: $authState. Persist: $persist" }
    _state.value = when {
      authState.isAuthorized -> TraktAuthState.LOGGED_IN
      else -> TraktAuthState.LOGGED_OUT
    }
    cacheAuthState(authState)
    logger.d { "[TraktAuthRepository] Updated AuthState: ${_state.value}" }

    if (persist) {
      // Persist auth state
      withContext(dispatchers.io) {
        if (authState.isAuthorized) {
          authStore.save(authState)
          logger.d { "[TraktAuthRepository] Saved state to AuthStore: $authState" }
        } else {
          authStore.clear()
          logger.d { "[TraktAuthRepository] Cleared AuthStore" }
        }
      }
    }
  }
}
