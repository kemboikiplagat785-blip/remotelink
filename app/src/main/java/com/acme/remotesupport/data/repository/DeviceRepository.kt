package com.acme.remotesupport.data.repository

import com.acme.remotesupport.data.remote.DeviceDto
import com.acme.remotesupport.data.remote.NewDevice
import com.acme.remotesupport.data.remote.NewPair
import com.acme.remotesupport.data.remote.PairDto
import com.acme.remotesupport.data.remote.Supabase
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** CRUD + realtime presence for the account's devices and their pairings. */
class DeviceRepository(private val ownerId: String) {
    private val client get() = Supabase.client

    suspend fun listDevices(): List<DeviceDto> =
        client.from("devices").select {
            filter { eq("owner_id", ownerId) }
        }.decodeList()

    suspend fun listPairs(): List<PairDto> =
        client.from("device_pairs").select {
            filter { eq("owner_id", ownerId) }
        }.decodeList()

    private suspend fun findByCode(code: String): DeviceDto? =
        client.from("devices").select {
            filter {
                eq("owner_id", ownerId)
                eq("device_code", code)
            }
        }.decodeSingleOrNull()

    /**
     * Register (or refresh) this device and return its id. [previousCode] is what this
     * device registered under before, so its row moves to the new code instead of the
     * phone appearing twice.
     */
    suspend fun registerSelf(device: NewDevice, previousCode: String? = null): DeviceDto {
        val existing = findByCode(device.deviceCode) ?: previousCode?.let { findByCode(it) }

        return if (existing == null) {
            client.from("devices").insert(device) { select() }.decodeSingle()
        } else {
            client.from("devices").update(
                buildJsonObject {
                    put("device_code", device.deviceCode)
                    put("name", device.name)
                    put("model", device.model)
                    put("manufacturer", device.manufacturer)
                    put("os_version", device.osVersion)
                    put("app_version", device.appVersion)
                    put("online", true)
                    put("unattended", device.unattended)
                },
            ) {
                select()
                filter { eq("id", existing.id) }
            }.decodeSingle()
        }
    }

    suspend fun setOnline(deviceId: String, online: Boolean) {
        client.from("devices").update(mapOf("online" to online)) {
            filter { eq("id", deviceId) }
        }
    }

    suspend fun setBroadcasting(deviceId: String, broadcasting: Boolean) {
        client.from("devices").update(mapOf("broadcasting" to broadcasting)) {
            filter { eq("id", deviceId) }
        }
    }

    suspend fun setBlocked(deviceId: String, blocked: Boolean) {
        client.from("devices").update(mapOf("blocked" to blocked)) {
            filter { eq("id", deviceId) }
        }
    }

    /** Mark whether this device offers unattended access (PIN-gated reconnect). */
    suspend fun setUnattended(deviceId: String, unattended: Boolean) {
        client.from("devices").update(mapOf("unattended" to unattended)) {
            filter { eq("id", deviceId) }
        }
    }

    suspend fun rename(deviceId: String, name: String) {
        client.from("devices").update(mapOf("name" to name)) {
            filter { eq("id", deviceId) }
        }
    }

    suspend fun delete(deviceId: String) {
        client.from("devices").delete { filter { eq("id", deviceId) } }
    }

    suspend fun pair(deviceA: String, deviceB: String) {
        client.from("device_pairs").insert(NewPair(ownerId, deviceA, deviceB))
    }

    /** Emits the device list whenever any device row for this owner changes. */
    fun observeDevices(): Flow<List<DeviceDto>> {
        val ch = client.channel("devices:$ownerId")
        // RLS already restricts rows to this owner; we reload the filtered list on
        // any change rather than depending on the version-specific realtime filter API.
        val changes = ch.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "devices"
        }.onStart { ch.subscribe() }.onCompletion { ch.unsubscribe() }
        return flow {
            emit(listDevices())
            changes.collect { emit(listDevices()) }
        }
    }
}
