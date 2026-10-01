package com.rork.rideax.model

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.roundToInt

/** Currency amount with exactly two decimals, e.g. "€12.40". */
fun formatMoney(value: Double, currencyCode: String): String {
    val format = NumberFormat.getCurrencyInstance(Locale.getDefault())
    runCatching { format.currency = Currency.getInstance(currencyCode.uppercase(Locale.ROOT)) }
    format.minimumFractionDigits = 2
    format.maximumFractionDigits = 2
    return format.format(value)
}

/** Whole-unit currency amount for chart labels, e.g. "€42". */
fun formatMoneyWhole(value: Double, currencyCode: String): String {
    val format = NumberFormat.getCurrencyInstance(Locale.getDefault())
    runCatching { format.currency = Currency.getInstance(currencyCode.uppercase(Locale.ROOT)) }
    format.minimumFractionDigits = 0
    format.maximumFractionDigits = 0
    return format.format(value)
}

fun formatOneDecimal(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)

fun formatRating(value: Double): String = String.format(Locale.getDefault(), "%.2f", value).trimEnd('0').trimEnd('.', ',')

/** Road distance, "850 m" or "4.2 km". */
fun formatDistance(meters: Double): String =
    if (meters < 1000) "${meters.roundToInt()} m" else "${formatOneDecimal(meters / 1000)} km"
