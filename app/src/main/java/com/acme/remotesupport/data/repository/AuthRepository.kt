package com.acme.remotesupport.data.repository

import com.acme.remotesupport.data.remote.ProfileDto
import com.acme.remotesupport.data.remote.Supabase
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.flow.StateFlow

/** Authentication and the signed-in user's profile. */
class AuthRepository {
    private val client get() = Supabase.client

    val sessionStatus: StateFlow<SessionStatus> = client.auth.sessionStatus

    val currentUserId: String? get() = client.auth.currentUserOrNull()?.id
    val currentEmail: String? get() = client.auth.currentUserOrNull()?.email

    suspend fun signIn(email: String, password: String) {
        client.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    suspend fun signUp(email: String, password: String) {
        client.auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    suspend fun sendPasswordReset(email: String) {
        client.auth.resetPasswordForEmail(email.trim())
    }

    suspend fun signOut() {
        client.auth.signOut()
    }

    suspend fun profile(): ProfileDto? {
        val uid = currentUserId ?: return null
        return client.from("profiles").select {
            filter { eq("id", uid) }
        }.decodeSingleOrNull()
    }

    suspend fun updateProfile(displayName: String?, phoneNumber: String?) {
        val uid = currentUserId ?: return
        client.from("profiles").update(
            mapOf("display_name" to displayName, "phone_number" to phoneNumber),
        ) { filter { eq("id", uid) } }
    }
}
