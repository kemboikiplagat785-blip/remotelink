package com.acme.remotesupport.data.repository

import com.acme.remotesupport.data.remote.NewAuditLog
import com.acme.remotesupport.data.remote.NewFeedback
import com.acme.remotesupport.data.remote.Supabase
import io.github.jan.supabase.postgrest.from

/** Audit trail and user feedback. */
class MiscRepository(private val ownerId: String) {
    private val client get() = Supabase.client

    suspend fun audit(event: String, detail: String? = null) {
        runCatching { client.from("audit_logs").insert(NewAuditLog(ownerId, event, detail)) }
    }

    suspend fun sendFeedback(message: String, contactInfo: String? = null) {
        client.from("feedback").insert(NewFeedback(ownerId, contactInfo, message))
    }
}
