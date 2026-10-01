package com.rork.rideax.ui.payments

import androidx.compose.ui.graphics.Color
import com.rork.rideax.data.StripeIntent
import com.rork.rideax.ui.theme.RideColors
import com.stripe.android.paymentsheet.PaymentSheet

/**
 * RideAX's navy/orange skin on Stripe's native sheet, so checkout never looks
 * like a different app. Card details are collected and held by Stripe.
 */
fun rideAxPaymentConfiguration(intent: StripeIntent): PaymentSheet.Configuration {
    val colors = PaymentSheet.Colors(
        primary = RideColors.Accent,
        surface = RideColors.Canvas,
        component = RideColors.Surface,
        componentBorder = RideColors.SurfaceRaised,
        componentDivider = RideColors.SurfaceRaised,
        onComponent = RideColors.TextPrimary,
        subtitle = RideColors.TextSecondary,
        placeholderText = RideColors.TextSecondary,
        onSurface = RideColors.TextPrimary,
        appBarIcon = RideColors.TextSecondary,
        error = RideColors.Danger,
    )
    val buttonColors = PaymentSheet.PrimaryButtonColors(
        background = RideColors.Accent,
        onBackground = RideColors.OnAccent,
        border = Color.Transparent,
    )
    val appearance = PaymentSheet.Appearance.Builder()
        .colorsLight(colors)
        .colorsDark(colors)
        .shapes(PaymentSheet.Shapes(14f, 1f))
        .primaryButton(
            PaymentSheet.PrimaryButton(
                colorsLight = buttonColors,
                colorsDark = buttonColors,
                shape = PaymentSheet.PrimaryButtonShape(cornerRadiusDp = 27f, heightDp = 54f),
            )
        )
        .build()

    val builder = PaymentSheet.Configuration.Builder("RideAX")
        .appearance(appearance)
        .allowsDelayedPaymentMethods(false)

    val customerId = intent.customerId
    val ephemeralKey = intent.ephemeralKeySecret
    if (customerId != null && ephemeralKey != null) {
        builder.customer(PaymentSheet.CustomerConfiguration(customerId, ephemeralKey))
    }
    return builder.build()
}
