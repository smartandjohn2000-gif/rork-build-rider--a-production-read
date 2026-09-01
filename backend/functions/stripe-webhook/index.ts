import Stripe from "https://esm.sh/stripe@17.7.0?target=deno";
import { createAdminClient, corsHeaders, json } from "../_shared/auth.ts";

/**
 * Stripe's source of truth for payment state.
 *
 * Public by design — it is called by Stripe, not the app — so it authenticates
 * with the webhook signature instead of a user JWT. The ride is only ever
 * marked paid here, never by the client.
 */

const STRIPE_SECRET_KEY = Deno.env.get("STRIPE_SECRET_KEY");
const STRIPE_WEBHOOK_SECRET = Deno.env.get("STRIPE_WEBHOOK_SECRET");

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  if (!STRIPE_SECRET_KEY || !STRIPE_WEBHOOK_SECRET) {
    console.error("stripe-webhook missing STRIPE_SECRET_KEY or STRIPE_WEBHOOK_SECRET");
    return json({ error: "stripe_not_configured" }, 503);
  }

  const signature = req.headers.get("stripe-signature");
  if (!signature) return json({ error: "missing_signature" }, 400);

  const stripe = new Stripe(STRIPE_SECRET_KEY, { apiVersion: "2025-08-27.basil" });
  const payload = await req.text();

  let event: Stripe.Event;
  try {
    event = await stripe.webhooks.constructEventAsync(payload, signature, STRIPE_WEBHOOK_SECRET);
  } catch (err) {
    console.error("stripe-webhook signature verification failed", err);
    return json({ error: "invalid_signature" }, 400);
  }

  const admin = createAdminClient();
  const now = new Date().toISOString();

  try {
    switch (event.type) {
      case "payment_intent.succeeded": {
        const intent = event.data.object as Stripe.PaymentIntent;
        const rideId = intent.metadata?.ride_id;

        await admin
          .from("payments")
          .update({
            status: "succeeded",
            failure_message: null,
            payment_method_label: describePaymentMethod(intent),
            updated_at: now,
          })
          .eq("stripe_payment_intent_id", intent.id);

        if (rideId) {
          await admin.from("rides").update({ status: "paid", updated_at: now }).eq("id", rideId);
        }
        break;
      }

      case "payment_intent.payment_failed": {
        const intent = event.data.object as Stripe.PaymentIntent;
        await admin
          .from("payments")
          .update({
            status: "failed",
            failure_message: intent.last_payment_error?.message ?? "Payment failed",
            updated_at: now,
          })
          .eq("stripe_payment_intent_id", intent.id);
        break;
      }

      case "payment_intent.canceled": {
        const intent = event.data.object as Stripe.PaymentIntent;
        await admin
          .from("payments")
          .update({ status: "canceled", updated_at: now })
          .eq("stripe_payment_intent_id", intent.id);
        break;
      }

      case "charge.succeeded": {
        const charge = event.data.object as Stripe.Charge;
        if (charge.payment_intent) {
          await admin
            .from("payments")
            .update({ receipt_url: charge.receipt_url, updated_at: now })
            .eq("stripe_payment_intent_id", String(charge.payment_intent));
        }
        break;
      }

      default:
        break;
    }
  } catch (err) {
    // Return 500 so Stripe retries — the DB write, not the signature, failed.
    console.error(`stripe-webhook failed handling ${event.type}`, err);
    return json({ error: "handler_failed" }, 500);
  }

  return json({ received: true });
});

function describePaymentMethod(intent: Stripe.PaymentIntent): string | null {
  const details = (intent.charges?.data?.[0] as Stripe.Charge | undefined)?.payment_method_details;
  if (!details) return null;
  if (details.card) {
    const brand = details.card.brand ?? "Card";
    return `${brand.charAt(0).toUpperCase()}${brand.slice(1)} •• ${details.card.last4 ?? "----"}`;
  }
  return details.type ?? null;
}
