import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

export class AuthError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "AuthError";
  }
}

export interface AuthUser {
  userId: string;
  email?: string;
  name?: string;
}

/**
 * Verifies the caller's native Supabase Auth token and returns their user.
 * The anon key + Authorization pair acts as that user for PostgREST too.
 */
export async function requireAuth(req: Request): Promise<AuthUser> {
  const authHeader = req.headers.get("Authorization") ?? "";
  if (!authHeader.toLowerCase().startsWith("bearer ")) throw new AuthError("Missing token");

  const supabase = createUserClient(req);
  const { data, error } = await supabase.auth.getUser(authHeader.slice(7).trim());
  if (error || !data.user) throw new AuthError(error?.message ?? "Authentication failed");

  return {
    userId: data.user.id,
    email: data.user.email ?? undefined,
    name:
      (data.user.user_metadata?.full_name as string | undefined) ??
      (data.user.user_metadata?.name as string | undefined),
  };
}

export function createUserClient(req: Request) {
  return createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_ANON_KEY")!, {
    global: { headers: { Authorization: req.headers.get("Authorization")! } },
  });
}

export function createAdminClient() {
  return createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
}

export const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type, stripe-signature",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
};

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}
