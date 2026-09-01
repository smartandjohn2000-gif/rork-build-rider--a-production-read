import Stripe from "https://esm.sh/stripe@17.7.0?target=deno";
import { requireAuth, createAdminClient, AuthError, corsHeaders, json } from "../_shared/auth.ts";

/**
 * Creates (or reuses) the Stripe PaymentIntent for a completed ride.
 *
 * The client never sends an amount. The charge is derived from the ride row in
 * Postgres, so a tampered client cannot pay less than the fare it was quoted.
 */

const STRIPE_SECRET_KEY = Deno.env.get("STRIPE_SECRET_KEY");
const STRIPE_PUBLISHABLE_KEY = Deno.env.get("STRIPE_PUBLISHABLE_KEY");
const DEFAULT_STRIPE_API_VERSION = "2025-08-27.basil";

interface RequestBody {
  rideId?: string;
  stripeApiVersion?: string;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const user = await requireAuth(req);

    if (!STRIPE_SECRET_KEY || !STRIPE_PUBLISHABLE_KEY) {
      // Surfaced to the app as a specific code so it can fall back to an
      // unpaid "settle later" state instead of showing a generic failure.
      return json({ error: "stripe_not_configured" }, 503);
    }

    const body = (await req.json().catch(() => ({}))) as RequestBody;
    const rideId = body.rideId;
    if (!rideId) return json({ error: "missing_ride_id" }, 400);

    const stripe = new Stripe(STRIPE_SECRET_KEY, { apiVersion: "2025-08-27.basil" });
    const admin = createAdminClient();

    const { data: ride, error: rideError } = await admin
      .from("rides")
      .select("id, rider_id, status, fare_total, currency, pickup_name, dropoff_name")
      .eq("id", rideId)
      .maybeSingle();

    if (rideError) throw rideError;
    if (!ride) return json({ error: "ride_not_found" }, 404);
    if (ride.rider_id !== user.userId) return json({ error: "forbidden" }, 403);

    const amountCents = Math.round(Number(ride.fare_total) * 100);
    if (!Number.isFinite(amountCents) || amountCents < 50) {
      return json({ error: "invalid_amount" }, 400);
    }
    const currency = (ride.currency ?? "eur").toLowerCase();

    // Reuse an in-flight intent for this ride so a retry never double-charges.
    const { data: existing } = await admin
      .from("payments")
      .select("id, status, stripe_payment_intent_id, amount_cents")
      .eq("ride_id", rideId)
      .maybeSingle();

    if (existing?.status === "succeeded") {
      return json({ error: "already_paid" }, 409);
    }

    // One Stripe customer per rider, so saved cards come back next trip.
    const { data: profile } = await admin
      .from("profiles")
      .select("id, email, full_name, stripe_customer_id")
      .eq("id", user.userId)
      .maybeSingle();

    let customerId = profile?.stripe_customer_id ?? null;
    if (!customerId) {
      const customer = await stripe.customers.create({
        email: profile?.email ?? user.email,
        name: profile?.full_name ?? user.name,
        metadata: { rork_user_id: user.userId },
      });
      customerId = customer.id;
      await admin.from("profiles").update({ stripe_customer_id: customerId }).eq("id", user.userId);
    }

    let paymentIntent: Stripe.PaymentIntent | null = null;
    if (existing?.stripe_payment_intent_id) {
      const found = await stripe.paymentIntents.retrieve(existing.stripe_payment_intent_id);
      const reusable = ["requires_payment_method", "requires_confirmation", "requires_action"];
      if (reusable.includes(found.status)) {
        paymentIntent =
          found.amount === amountCents
            ? found
            : await stripe.paymentIntents.update(found.id, { amount: amountCents });
      }
    }

    if (!paymentIntent) {
      paymentIntent = await stripe.paymentIntents.create({
        amount: amountCents,
        currency,
        customer: customerId,
        setup_future_usage: "off_session",
        automatic_payment_methods: { enabled: true },
        description: `RideAX · ${ride.pickup_name} → ${ride.dropoff_name}`,
        metadata: { ride_id: String(ride.id), rider_id: user.userId },
      });
    }

    const ephemeralKey = await stripe.ephemeralKeys.create(
      { customer: customerId },
      { apiVersion: body.stripeApiVersion ?? DEFAULT_STRIPE_API_VERSION },
    );

    const { data: payment, error: paymentError } = await admin
      .from("payments")
      .upsert(
        {
          ...(existing?.id ? { id: existing.id } : {}),
          ride_id: ride.id,
          rider_id: user.userId,
          amount_cents: amountCents,
          currency,
          status: paymentIntent.status,
          stripe_payment_intent_id: paymentIntent.id,
          updated_at: new Date().toISOString(),
        },
        { onConflict: "stripe_payment_intent_id" },
      )
      .select("id")
      .single();

    if (paymentError) throw paymentError;

    return json({
      paymentId: payment.id,
      paymentIntentClientSecret: paymentIntent.client_secret,
      ephemeralKeySecret: ephemeralKey.secret,
      customerId,
      publishableKey: STRIPE_PUBLISHABLE_KEY,
      amountCents,
      currency,
    });
  } catch (err) {
    if (err instanceof AuthError) return json({ error: "unauthorized" }, 401);
    console.error("create-ride-payment failed", err);
    return json({ error: "internal_error" }, 500);
  }
});
