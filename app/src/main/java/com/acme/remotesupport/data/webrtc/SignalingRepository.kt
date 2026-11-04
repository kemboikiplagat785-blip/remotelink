package com.acme.remotesupport.data.webrtc

import com.acme.remotesupport.data.remote.NewSignal
import com.acme.remotesupport.data.remote.SignalDto
import com.acme.remotesupport.data.remote.Supabase
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Relays WebRTC signaling (SDP offer/answer and ICE candidates) between the two
 * devices through the Supabase `signals` table + Realtime. Each side writes
 * signals addressed to the other and listens for signals addressed to itself.
 */
class SignalingRepository(private val ownerId: String) {
    private val client get() = Supabase.client
    private val json = ControlEvent.json

    suspend fun send(
        sessionId: String,
        from: String,
        to: String,
        kind: String,          // offer | answer | ice | bye
        payload: String,
    ) {
        client.from("signals").insert(
            NewSignal(ownerId, sessionId, from, to, kind, payload),
        )
    }

    /**
     * Inbound signals addressed to [selfDevice] for [sessionId], oldest first.
     *
     * The target only starts listening after its user approves capture, by which
     * time the controller has already sent its offer. Realtime does not replay, so
     * rows already in the table are read once the subscription is live.
     */
    fun incoming(sessionId: String, selfDevice: String): Flow<SignalDto> = channelFlow {
        val ch = client.channel("signals:$selfDevice:$sessionId")
        val live = ch.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = "signals"
        }
        val seen = ConcurrentHashMap.newKeySet<String>()

        launch(start = CoroutineStart.UNDISPATCHED) {
            live.collect { action ->
                // action.record is the inserted row as a JsonObject.
                val signal = runCatching {
                    json.decodeFromJsonElement(SignalDto.serializer(), action.record)
                }.getOrNull() ?: return@collect
                if (signal.sessionId == sessionId && signal.recipientDevice == selfDevice && seen.add(signal.id)) {
                    send(signal)
                }
            }
        }

        try {
            ch.subscribe(blockUntilSubscribed = true)
            client.from("signals").select {
                filter {
                    eq("session_id", sessionId)
                    eq("recipient_device", selfDevice)
                }
                order("created_at", Order.ASCENDING)
            }.decodeList<SignalDto>().forEach { if (seen.add(it.id)) send(it) }
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { runCatching { ch.unsubscribe() } }
        }
    }
}
