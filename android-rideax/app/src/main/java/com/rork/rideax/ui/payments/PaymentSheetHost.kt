package com.rork.rideax.ui.payments

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.rork.rideax.ui.rider.PaymentSheetOutcome
import com.rork.rideax.ui.rider.RideViewModel
import com.stripe.android.PaymentConfiguration
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import kotlinx.coroutines.CompletableDeferred

private class PendingPayment {
    var deferred: CompletableDeferred<PaymentSheetOutcome>? = null
}

/**
 * Hosts Stripe's PaymentSheet and exposes it to the ride ViewModel as a
 * suspend call. Must stay in composition for the activity's lifetime.
 */
@Composable
fun PaymentSheetHost(viewModel: RideViewModel) {
    val context = LocalContext.current
    val pending = remember { PendingPayment() }
    val sheet = remember {
        PaymentSheet.Builder { result ->
            val outcome = when (result) {
                is PaymentSheetResult.Completed -> PaymentSheetOutcome.Completed
                is PaymentSheetResult.Canceled -> PaymentSheetOutcome.Canceled
                is PaymentSheetResult.Failed -> PaymentSheetOutcome.Failed(result.error.localizedMessage ?: "Payment failed. Please try again.")
            }
            pending.deferred?.complete(outcome)
            pending.deferred = null
        }
    }.build()

    DisposableEffect(viewModel, sheet) {
        viewModel.presentPaymentSheet = { intent ->
            PaymentConfiguration.init(context.applicationContext, intent.publishableKey)
            val deferred = CompletableDeferred<PaymentSheetOutcome>()
            pending.deferred = deferred
            sheet.presentWithPaymentIntent(intent.paymentIntentClientSecret, rideAxPaymentConfiguration(intent))
            deferred.await()
        }
        onDispose { viewModel.presentPaymentSheet = null }
    }
}
