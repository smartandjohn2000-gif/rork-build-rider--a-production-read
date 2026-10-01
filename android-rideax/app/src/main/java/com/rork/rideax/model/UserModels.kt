package com.rork.rideax.model

import kotlinx.serialization.Serializable

/** Which side of the marketplace the person is using right now. */
@Serializable
enum class AppRole(val wire: String, val title: String, val ctaTitle: String) {
    RIDER("rider", "Rider", "Continue as rider"),
    DRIVER("driver", "Driver", "Continue as driver"),
}

/** The signed-in account. A single account can hold both profiles. */
@Serializable
data class UserAccount(
    val id: String,
    val fullName: String,
    val email: String,
    val phone: String = "",
    val role: AppRole = AppRole.RIDER,
    val rating: Double = 5.0,
    val memberSince: Long,
    val avatarUrl: String? = null,
) {
    val firstName: String get() = fullName.split(" ").firstOrNull()?.takeIf { it.isNotBlank() } ?: fullName
    val initials: String
        get() = fullName.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "R" }
}

/** Verification state for the driver side, mirroring admin approval. */
@Serializable
enum class DriverApprovalStatus(val wire: String, val title: String, val detail: String) {
    NOT_SUBMITTED("notSubmitted", "Documents required", "Upload your licence, insurance and vehicle details to start driving."),
    PENDING("pending", "Awaiting approval", "Our team is reviewing your documents. This usually takes under 24 hours."),
    APPROVED("approved", "Approved to drive", "You're verified. Go online whenever you're ready to earn."),
    REJECTED("rejected", "Action required", "One of your documents couldn't be verified. Please re-upload."),
}

@Serializable
enum class DocumentKind { LICENCE, INSURANCE, REGISTRATION, PHOTO }

/** A document the driver must supply before admin approval. */
@Serializable
data class DriverDocument(
    val id: String,
    val title: String,
    val detail: String,
    val kind: DocumentKind,
    val isUploaded: Boolean,
) {
    companion object {
        val required: List<DriverDocument> = listOf(
            DriverDocument("licence", "Driving licence", "Front and back, valid for at least 3 months", DocumentKind.LICENCE, true),
            DriverDocument("insurance", "Vehicle insurance", "Certificate showing commercial cover", DocumentKind.INSURANCE, true),
            DriverDocument("registration", "Vehicle registration", "Matching the plate on your profile", DocumentKind.REGISTRATION, true),
            DriverDocument("profile", "Profile photo", "Clear photo of your face, no sunglasses", DocumentKind.PHOTO, false),
        )
    }
}

/** The driver's own vehicle record. */
@Serializable
data class Vehicle(
    val make: String,
    val model: String,
    val year: Int,
    val color: String,
    val plate: String,
) {
    val displayName: String get() = "$color $make $model"
}
