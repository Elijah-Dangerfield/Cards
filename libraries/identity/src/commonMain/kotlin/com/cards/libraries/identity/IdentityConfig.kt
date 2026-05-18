package com.dangerfield.cards.libraries.identity

/**
 * Per-environment Supabase wiring. Default binding in
 * `:libraries:identity:impl` points at the dev project; flip via build
 * variants when prod ships.
 *
 * `supabaseAnonKey` is the project's **anon (public)** API key — designed
 * to be exposed in client builds. It's gated by Supabase Row Level
 * Security policies, not by secrecy. Don't confuse with the service-role
 * key (server-only).
 */
interface IdentityConfig {
    /** e.g. `https://yuqrfhdoejonclgbixlw.supabase.co`. */
    val supabaseUrl: String

    /** Public anon key — Supabase Project Settings → API → `anon` `public`. */
    val supabaseAnonKey: String
}
