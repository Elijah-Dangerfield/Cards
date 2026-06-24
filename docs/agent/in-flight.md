# In-flight (worker handoff log)

## feat(shop): cut XP boost to 5 min at 1000 chips (GAME-5)

**Problem:** Owner directive — the 2x XP boost ran 30 minutes for 5000 chips; it should be a 5-minute window costing 1000 chips.
**Approach:** Flipped `XP_BOOST_DEFAULT_DURATION_MS` to 5 min (all banner/badge/countdown fractions read this constant, so they follow automatically). Repriced the catalog via a new append-only migration `V71__xp_boost_5min_1000_chips.sql` (server is the source of truth for the live shop) and updated the duration copy in `subtitle/description` locales. Swept user-facing copy in strings.xml + the preview catalog + comments.
**Reviewer notes:** Price lives in the DB catalog row, not a Kotlin constant — the preview fallback catalog and the shop VM test fixture are the only client-side prices, both updated to 1000. The `profile_boost_confirm_message` string still uses `\'` escapes (pre-existing AGENTS.md violation); left untouched to keep this change scoped.
