package com.acme.remotesupport.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// DTOs mirror the Supabase schema (snake_case columns). kotlinx.serialization maps
// JSON names via @SerialName; the Supabase Kotlin client uses these for Postgrest.

@Serializable
data class DeviceDto(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("device_code") val deviceCode: String,
    val name: String,
    val model: String? = null,
    val manufacturer: String? = null,
    val platform: String = "android",
    @SerialName("os_version") val osVersion: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    val online: Boolean = false,
    val broadcasting: Boolean = false,
    val blocked: Boolean = false,
    val unattended: Boolean = false,
    @SerialName("battery_level") val batteryLevel: Int? = null,
    val charging: Boolean? = null,
    @SerialName("network_type") val networkType: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("last_screenshot") val lastScreenshot: String? = null,
    @SerialName("last_screenshot_at") val lastScreenshotAt: String? = null,
)

@Serializable
data class NewDevice(
    @SerialName("owner_id") val ownerId: String,
    @SerialName("device_code") val deviceCode: String,
    val name: String,
    val model: String? = null,
    val manufacturer: String? = null,
    val platform: String = "android",
    @SerialName("os_version") val osVersion: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    val online: Boolean = true,
    val broadcasting: Boolean = false,
    val unattended: Boolean = false,
)

@Serializable
data class DeviceScreenshotDto(
    val id: String,
    @SerialName("last_screenshot") val lastScreenshot: String? = null,
    @SerialName("last_screenshot_at") val lastScreenshotAt: String? = null,
)

@Serializable
data class PairDto(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("device_a") val deviceA: String,
    @SerialName("device_b") val deviceB: String,
)

@Serializable
data class NewPair(
    @SerialName("owner_id") val ownerId: String,
    @SerialName("device_a") val deviceA: String,
    @SerialName("device_b") val deviceB: String,
)

@Serializable
data class SessionDto(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("controller_device") val controllerDevice: String,
    @SerialName("target_device") val targetDevice: String,
    val mode: String = "control",
    val status: String = "pending",
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("end_reason") val endReason: String? = null,
)

@Serializable
data class NewSession(
    @SerialName("owner_id") val ownerId: String,
    @SerialName("controller_device") val controllerDevice: String,
    @SerialName("target_device") val targetDevice: String,
    val mode: String = "control",
    val status: String = "pending",
)

@Serializable
data class SignalDto(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("sender_device") val senderDevice: String,
    @SerialName("recipient_device") val recipientDevice: String,
    val kind: String,
    val payload: String,
)

@Serializable
data class NewSignal(
    @SerialName("owner_id") val ownerId: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("sender_device") val senderDevice: String,
    @SerialName("recipient_device") val recipientDevice: String,
    val kind: String,
    val payload: String,
)

@Serializable
data class ProfileDto(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("phone_number") val phoneNumber: String? = null,
)

@Serializable
data class SubscriptionDto(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    val status: String,
    @SerialName("trial_ends_at") val trialEndsAt: String? = null,
    @SerialName("current_period_end") val currentPeriodEnd: String? = null,
)

@Serializable
data class MpesaTransactionDto(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("phone_number") val phoneNumber: String,
    val amount: Double,
    @SerialName("checkout_request_id") val checkoutRequestId: String? = null,
    val status: String = "pending",
    @SerialName("result_desc") val resultDesc: String? = null,
)

@Serializable
data class NewAuditLog(
    @SerialName("owner_id") val ownerId: String,
    val event: String,
    val detail: String? = null,
)

@Serializable
data class NewFeedback(
    @SerialName("owner_id") val ownerId: String,
    @SerialName("contact_info") val contactInfo: String? = null,
    val message: String,
)
