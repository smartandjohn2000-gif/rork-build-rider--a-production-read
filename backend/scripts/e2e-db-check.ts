/**
 * Server-side end-to-end probe of the RideAX database plumbing.
 *
 * Uses the service role key (never printed) to verify exactly what the iOS
 * and Android apps depend on at runtime:
 *   1. the `profiles` upsert both apps send at sign-in,
 *   2. a realistic `rides` insert with the fare breakdown JSON,
 *   3. the `payments` upsert conflict target (`stripe_payment_intent_id`
 *      must carry a UNIQUE constraint, or create-ride-payment 500s),
 *   4. that RLS still hides rows from the anonymous key.
 *
 * Every row it creates is deleted again before it exits. Run with:
 *   rork env run -- bun backend/scripts/e2e-db-check.ts
 */

const url = (process.env.EXPO_PUBLIC_SUPABASE_URL ?? "").replace(/\/$/, "");
const service = process.env.SUPABASE_SERVICE_ROLE_KEY ?? "";
const anon = process.env.EXPO_PUBLIC_SUPABASE_ANON_KEY ?? "";

if (!url || !service || !anon) {
  console.error("FAIL missing env (EXPO_PUBLIC_SUPABASE_URL / ANON_KEY / SERVICE_ROLE_KEY)");
  process.exit(1);
}

const USER_ID = "e2e-probe-user-rideax";
const RIDE_ID = "00000000-0000-0000-0000-00000000e2e1";
const PI_ID = "pi_e2e_probe_rideax";

let failures = 0;
function check(name: string, ok: boolean, detail = "") {
  console.log(`${ok ? "PASS" : "FAIL"} ${name}${detail ? ` — ${detail}` : ""}`);
  if (!ok) failures += 1;
}

async function db(
  method: string,
  table: string,
  body?: unknown,
  prefer?: string,
  key: string = service,
  query = "",
) {
  const response = await fetch(`${url}/rest/v1/${table}${query}`, {
    method,
    headers: {
      apikey: key,
      Authorization: `Bearer ${key}`,
      "Content-Type": "application/json",
      ...(prefer ? { Prefer: prefer } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  let json: unknown = null;
  try { json = text ? JSON.parse(text) : null; } catch { json = text; }
  return { status: response.status, ok: response.ok, json, text: text.slice(0, 300) };
}

async function main() {
  // 0. RLS: the anonymous key must see nothing.
  const anonRides = await db("GET", "rides", undefined, undefined, anon, "?select=id&limit=5");
  check("RLS hides rides from anon key", anonRides.ok && Array.isArray(anonRides.json) && anonRides.json.length === 0);

  // 1. Profile upsert (merge-duplicates, as both apps send it).
  const profile = await db(
    "POST", "profiles",
    { id: USER_ID, email: "e2e-probe@rideax.local", full_name: "E2E Probe", role: "rider", updated_at: new Date().toISOString() },
    "resolution=merge-duplicates,return=minimal",
  );
  check("profiles upsert (merge-duplicates)", profile.ok || profile.status === 409, profile.ok ? "" : profile.text);

  // 2. Realistic ride insert with fare_breakdown JSON.
  const ride = await db(
    "POST", "rides",
    {
      id: RIDE_ID,
      rider_id: USER_ID,
      status: "completed",
      ride_class: "go",
      pickup_name: "Gare de Lyon",
      pickup_subtitle: "Paris, France",
      pickup_lat: 48.8443,
      pickup_lng: 2.3743,
      dropoff_name: "Eiffel Tower",
      dropoff_subtitle: "Paris, France",
      dropoff_lat: 48.8584,
      dropoff_lng: 2.2945,
      distance_km: 5.2,
      duration_min: 14,
      currency: "eur",
      fare_total: 12.4,
      surge_multiplier: 1,
      fare_breakdown: { base: 2.6, perKm: 1.1, kilometres: 5.2, perMinute: 0.28, minutes: 14, booking: 1.2, minimum: 6.5, surge: 1, total: 12.4, currencyCode: "EUR" },
    },
    "return=representation",
    service,
    "?select=id",
  );
  check("rides insert with fare_breakdown", ride.ok && Array.isArray(ride.json) && ride.json.length === 1, ride.ok ? "" : ride.text);

  // 3. Payment upsert conflict target: the second insert with the same
  //    stripe_payment_intent_id must be rejected (UNIQUE constraint).
  const payment = { ride_id: RIDE_ID, rider_id: USER_ID, amount_cents: 1240, currency: "eur", status: "requires_payment_method", stripe_payment_intent_id: PI_ID };
  const first = await db("POST", "payments", payment, "resolution=merge-duplicates,return=minimal");
  check("payments insert (service role)", first.ok, first.ok ? "" : first.text);
  const second = await db("POST", "payments", { ...payment, id: undefined }, "return=minimal");
  check("payments UNIQUE on stripe_payment_intent_id", second.status === 409, `second insert status ${second.status}`);

  // Cleanup.
  await db("DELETE", "payments", undefined, undefined, service, `?ride_id=eq.${RIDE_ID}`);
  await db("DELETE", "rides", undefined, undefined, service, `?id=eq.${RIDE_ID}`);
  await db("DELETE", "profiles", undefined, undefined, service, `?id=eq.${USER_ID}`);
  console.log(failures === 0 ? "ALL PASS" : `${failures} FAILURE(S)`);
  process.exit(failures === 0 ? 0 : 1);
}

main();
