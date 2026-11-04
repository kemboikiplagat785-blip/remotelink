package com.acme.remotesupport.data.repository

import com.acme.remotesupport.data.remote.MpesaTransactionDto
import com.acme.remotesupport.data.remote.SubscriptionDto
import com.acme.remotesupport.data.remote.Supabase
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

class SubscriptionRepository(private val ownerId: String) {
    private val client get() = Supabase.client

    suspend fun current(): SubscriptionDto? =
        client.from("subscriptions").select {
            filter { eq("owner_id", ownerId) }
        }.decodeSingleOrNull()

    fun observe(): Flow<SubscriptionDto?> {
        val ch = client.channel("subscription:$ownerId")
        val changes = ch.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "subscriptions"
        }.onStart { ch.subscribe() }.onCompletion { ch.unsubscribe() }
        return flow {
            emit(current())
            changes.collect { emit(current()) }
        }
    }

    suspend fun latestTransaction(): MpesaTransactionDto? =
        client.from("mpesa_transactions").select {
            filter { eq("owner_id", ownerId) }
            order("created_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
            limit(1)
        }.decodeSingleOrNull()
}
