package com.acme.remotesupport.data.repository

import com.acme.remotesupport.data.remote.Supabase
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class StkPushRequest(val phone: String)
@Serializable data class StkPushResponse(val ok: Boolean = false, val checkoutRequestId: String? = null)

@Serializable data class SendSmsRequest(val to: String, val message: String)
@Serializable data class SendSmsResponse(val ok: Boolean = false)

/** Calls the M-Pesa and SMS edge functions (server holds the provider secrets). */
class BillingRepository {
    private val client get() = Supabase.client
    private val json = Json { ignoreUnknownKeys = true }

    /** Kick off an STK push; the result arrives asynchronously via the callback
     *  function, which flips the subscription to active. Observe via SubscriptionRepository.
     *  The amount is set by the edge function. */
    suspend fun startStkPush(phone: String): StkPushResponse =
        json.decodeFromString(
            client.functions.invoke(
                function = "mpesa-stk-push",
                body = StkPushRequest(phone),
            ).bodyAsText(),
        )

    suspend fun sendSms(to: String, message: String): SendSmsResponse =
        json.decodeFromString(
            client.functions.invoke(
                function = "send-sms",
                body = SendSmsRequest(to, message),
            ).bodyAsText(),
        )
}
