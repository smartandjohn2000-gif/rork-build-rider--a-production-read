import Foundation
import Supabase

/// The managed Postgres backend.
///
/// Every request carries the signed-in rider's Supabase Auth session, which
/// Postgres reads in its row-level security policies — a rider can only ever
/// select their own rides and payments, and payment rows are writable
/// exclusively by the Stripe edge functions running under the service role.
/// The same client owns the session, so attaching it is automatic.
nonisolated enum Backend {
    static let client = SupabaseClient(
        supabaseURL: URL(string: Config.EXPO_PUBLIC_SUPABASE_URL) ?? URL(string: "https://unconfigured.supabase.co")!,
        supabaseKey: Config.EXPO_PUBLIC_SUPABASE_ANON_KEY
    )

    /// False in previews or before the backend env vars are injected, so the
    /// app can degrade to local-only behaviour instead of throwing on launch.
    static var isConfigured: Bool {
        !Config.EXPO_PUBLIC_SUPABASE_URL.isEmpty && !Config.EXPO_PUBLIC_SUPABASE_ANON_KEY.isEmpty
    }
}
