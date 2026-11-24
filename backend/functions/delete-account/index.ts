import { requireAuth, createAdminClient, corsHeaders, json, AuthError } from "../_shared/auth.ts";

/**
 * Deletes the signed-in person's account and their app data.
 *
 * Runs under the service role because auth users can only be removed
 * server-side. Rides, payments and driver rows are removed first so the
 * auth user's foreign data cannot outlive the account. This is irreversible:
 * the client confirms intent before calling.
 */
Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const user = await requireAuth(req);
    const admin = createAdminClient();

    // App data owned by this user. Payments written by the edge functions are
    // tied to rides; deleting by rider_id covers both shapes.
    await admin.from("payments").delete().eq("rider_id", user.userId);
    await admin.from("rides").delete().eq("rider_id", user.userId);
    await admin.from("rides").update({ driver_id: null }).eq("driver_id", user.userId);
    await admin.from("driver_profiles").delete().eq("id", user.userId);
    await admin.from("profiles").delete().eq("id", user.userId);

    // The auth user itself (service role, server only).
    const { error: deleteError } = await admin.auth.admin.deleteUser(user.userId);
    if (deleteError) throw deleteError;

    return json({ deleted: true });
  } catch (err) {
    if (err instanceof AuthError) return json({ error: "unauthorized" }, 401);
    console.error("delete-account failed", err);
    return json({ error: "internal_error" }, 500);
  }
});
