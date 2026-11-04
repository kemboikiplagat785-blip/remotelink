package com.acme.remotesupport.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acme.remotesupport.data.ServiceLocator
import com.acme.remotesupport.data.remote.DeviceDto
import com.acme.remotesupport.ui.AppCoordinator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DeviceGridUiState(
    val devices: List<DeviceDto> = emptyList(),
    val selfDeviceId: String? = null,
    val error: String? = null,
)

class DeviceGridViewModel : ViewModel() {
    private val repo = ServiceLocator.devices

    val uiState: StateFlow<DeviceGridUiState> =
        combine(
            repo.observeDevices().catch { emit(emptyList()) },
            AppCoordinator.selfDeviceId,
        ) { devices, selfId ->
            DeviceGridUiState(devices = devices, selfDeviceId = selfId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceGridUiState())

    fun rename(deviceId: String, name: String) = viewModelScope.launch {
        runCatching { repo.rename(deviceId, name) }
    }

    fun delete(deviceId: String) = viewModelScope.launch {
        runCatching { repo.delete(deviceId) }
    }

    fun setBlocked(deviceId: String, blocked: Boolean) = viewModelScope.launch {
        runCatching { repo.setBlocked(deviceId, blocked) }
    }
}
