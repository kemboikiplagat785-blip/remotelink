package com.acme.remotesupport.data.repository

import com.acme.remotesupport.data.remote.NewSession
import com.acme.remotesupport.data.remote.SessionDto
import com.acme.remotesupport.data.remote.Supabase
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

/** Lifecycle of remote-control sessions: pending -> active -> ended/abandoned. */
class SessionRepository(private val ownerId: String) {
    private val client get() = Supabase.client

    /** Controller asks to start controlling a target. */
    suspend fun startSession(controllerDevice: String, targetDevice: String, mode: String): SessionDto =
        client.from("remote_sessions").insert(
            NewSession(ownerId, controllerDevice, targetDevice, mode, status = "pending"),
        ) { select() }.decodeSingle()

    suspend fun markActive(sessionId: String) {
        client.from("remote_sessions").update(
            mapOf("status" to "active", "started_at" to nowIso()),
        ) { filter { eq("id", sessionId) } }
    }

    suspend fun endSession(sessionId: String, reason: String) {
        client.from("remote_sessions").update(
            mapOf("status" to "ended", "end_reason" to reason),
        ) { filter { eq("id", sessionId) } }
    }

    suspend fun markAbandoned(sessionId: String) {
        client.from("remote_sessions").update(mapOf("status" to "abandoned")) {
            filter { eq("id", sessionId) }
        }
    }

    /** End every live session targeting this device (controller "emergency stop"). */
    suspend fun emergencyStop(targetDevice: String) {
        client.from("remote_sessions").update(
            mapOf("status" to "ended", "end_reason" to "emergency_stop"),
        ) {
            filter {
                eq("target_device", targetDevice)
                isIn("status", listOf("pending", "active"))
            }
        }
    }

    suspend fun pendingFor(targetDevice: String): List<SessionDto> =
        client.from("remote_sessions").select {
            filter {
                eq("target_device", targetDevice)
                eq("status", "pending")
                // Controllers give up well before this, so an older row has been left behind.
                gte("created_at", java.time.Instant.now().minusSeconds(PENDING_MAX_AGE_SECONDS).toString())
            }
        }.decodeList()

    /** The target device watches for sessions it must serve. */
    fun observeIncoming(targetDevice: String): Flow<List<SessionDto>> {
        val ch = client.channel("sessions:$targetDevice")
        return ch.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "remote_sessions"
        }
            .onStart { ch.subscribe() }
            .onCompletion { ch.unsubscribe() }
            .let { flow ->
                kotlinx.coroutines.flow.flow {
                    emit(pendingFor(targetDevice))
                    flow.collect { emit(pendingFor(targetDevice)) }
                }
            }
    }

    private fun nowIso(): String = java.time.Instant.now().toString()

    private companion object {
        const val PENDING_MAX_AGE_SECONDS = 120L
    }
}
