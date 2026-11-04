package com.acme.remotesupport.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acme.remotesupport.data.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthMode { SIGN_IN, SIGN_UP }

data class AuthUiState(
    val mode: AuthMode = AuthMode.SIGN_IN,
    val email: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
)

class AuthViewModel : ViewModel() {
    private val auth = ServiceLocator.auth
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state

    fun onEmail(v: String) = _state.update { it.copy(email = v, error = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, error = null) }
    fun toggleMode() = _state.update {
        it.copy(mode = if (it.mode == AuthMode.SIGN_IN) AuthMode.SIGN_UP else AuthMode.SIGN_IN, error = null)
    }

    fun submit() {
        val s = _state.value
        if (s.email.isBlank() || s.password.length < 6) {
            _state.update { it.copy(error = "Enter an email and a password of at least 6 characters.") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = runCatching {
                if (s.mode == AuthMode.SIGN_IN) auth.signIn(s.email, s.password)
                else auth.signUp(s.email, s.password)
            }
            _state.update {
                result.fold(
                    // Navigation follows the session status. With email confirmation on,
                    // sign-up succeeds without a session, so the user must be told.
                    onSuccess = { _ ->
                        if (auth.currentUserId != null) it.copy(loading = false)
                        else it.copy(
                            loading = false,
                            mode = AuthMode.SIGN_IN,
                            error = "Check your email to confirm your account, then sign in.",
                        )
                    },
                    onFailure = { e -> it.copy(loading = false, error = e.message ?: "Authentication failed.") },
                )
            }
        }
    }
}
