package com.acme.remotesupport.ui.subscription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acme.remotesupport.data.ServiceLocator
import com.acme.remotesupport.data.remote.SubscriptionDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SubscriptionUiState(
    val subscription: SubscriptionDto? = null,
    val phone: String = "",
    val processing: Boolean = false,
    val message: String? = null,
)

class SubscriptionViewModel : ViewModel() {
    private val repo = ServiceLocator.subscription
    private val billing = ServiceLocator.billing

    private val _state = MutableStateFlow(SubscriptionUiState())
    val state: StateFlow<SubscriptionUiState> = _state

    /** Monthly price in KES, for display. Keep in sync with SUBSCRIPTION_PRICE_KES on the server. */
    val priceKes = 250

    init {
        viewModelScope.launch {
            repo.observe()
                .catch { e -> _state.update { it.copy(message = e.message ?: "Could not load subscription.") } }
                .collect { sub -> _state.update { it.copy(subscription = sub) } }
        }
    }

    fun onPhone(v: String) = _state.update { it.copy(phone = v, message = null) }

    fun pay() {
        val phone = _state.value.phone
        if (phone.isBlank()) {
            _state.update { it.copy(message = "Enter your M-Pesa phone number.") }
            return
        }
        _state.update { it.copy(processing = true, message = null) }
        viewModelScope.launch {
            val result = runCatching { billing.startStkPush(phone) }
            _state.update {
                result.fold(
                    onSuccess = { r ->
                        it.copy(
                            processing = false,
                            message = if (r.ok) "Check your phone and enter your M-Pesa PIN." else "Could not start payment.",
                        )
                    },
                    onFailure = { e -> it.copy(processing = false, message = e.message ?: "Payment failed.") },
                )
            }
        }
    }
}
