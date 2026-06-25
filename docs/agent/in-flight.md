# In-flight

## fix(auth): offline cold-boot reads as connection problem, not account-less (AUTH-6)

**Problem:** On a no-internet cold boot off a cached/fallback profile, creating or joining an MP room surfaced the account-less "Sign in first to create a room" copy, and a sign-out → continue-as-guest skipped the Home "new here?" tutorial banner.

**Approach:** Two targeted fixes in the load/fallback chain.
1. `LobbyViewModel` now observes the resolved profile shape and carries an `isFallbackProfile` flag. A `CreateRoomOutcome.NotSignedIn` / `JoinRoomOutcome.NotSignedIn` (the 401 you get when no session was ever minted) is recolored to the **connection** error (`CreateNetworkError` / `JoinNetworkError`) when the profile is a `Profile.Fallback`. A genuine `Profile.Authenticated` whose token chain ran dry still reads as "sign in first." **Directional call:** I keyed the recolor on the profile *shape* (`Profile.Fallback` = no confirmed server session) rather than threading `NetworkReachability` through the VM — the profile is already injected and observed, and Fallback is exactly "we never got a real session," which is the offline-cold-boot population AUTH-6 names. The rejected alternative (gate on live reachability) is noisier and races the banner's own witnessed-reachability signal.
2. `AppData.resetAccountScoped()` now resets `tutorialBannerDismissed`, so a full identity teardown (sign-out / account switch) re-offers the "new here?" walkthrough to the next guest. **Directional call:** this reclassifies the banner dismissal from device-scoped to identity-scoped — it still survives backgrounding for the *same* user, but a deliberate continue-as-guest is a fresh start. This intentionally overrides the old `UserScopedAppDataResetTest` assertion that the flag survives reset; I updated that test + added one pinning the new behaviour.

**Reviewer notes:** The "offline writes queue rather than hard-error" clause of AUTH-6 is already satisfied by existing infra (`UpdateProfileOutcome.Queued` + `ProfileRepository.flushPendingEdits`, and `ChipsRepositoryImpl.sync` replay), so no new work there — the two symptoms above were the actual gaps. Room create/join can't meaningfully queue (they need a live socket), which is why they surface a connection error instead. Tested: `LobbyViewModelTest` (3 new cases — fallback create→connection, real-account create→sign-in, fallback join→connection) and `UserScopedAppDataResetTest` (banner-reset case). Updated QA `ONB-10` + `ONB-11` to drop the "currently does not show" caveats.

**Deferred:** AUTH-5 (gating verification pass) is adjacent and partly exercised by the QA edits here, but a full Home/Shop/Profile/Claim/Inventory/Settings walk wants device eyes — reviewer please triage whether the QA cross-references suffice or it needs its own commit.

## chore(resources): refine device-verify banner copy (AUTH-1)

**Problem:** The `auth_verify_email_banner_*` strings felt under-built / terse and didn't echo the verify screen's voice.

**Approach:** Rewrote the five banner strings to match the screen's warm, second-person voice and to mirror its CTAs ("check again" echoes the "Check verification" button; "return here and we'll check with our server" body). No backslash escapes or em dashes (used hyphens). The pixel **placement** half of AUTH-1 stays deferred — it needs Studio to eyeball the banner's position relative to the verify CTA — so I rewrote the todo to describe only that remaining gap rather than removing AUTH-1.

**Reviewer notes:** Copy-only; no logic change. Placement deferred by design (the todo item explicitly flags it as Studio-gated).

## docs(qa): document the offline gating matrix as a single pass (AUTH-5)

**Problem:** AUTH-5 asked for a verification pass confirming every network-required surface (Home / Shop / Profile / Edit Profile / Claim / Inventory / Multiplayer / Settings) honors the Profile.Fallback gating rule — reads render cached, server-mutating soft-gates, money + MP hard-gate — and to ship any concrete gating fixes found.

**Approach:** Read each surface's VM/entry-point against the rule. **All eight already comply** — no code fix was warranted, so I shipped the verification as a QA matrix entry (`AUTH-5` under a new "Offline gating" section in `docs/QA.md`) that walks each surface once and names which gating column it lands in, cross-referencing `ONB-6`/`ONB-10` for the reach-this-state setup. Removed AUTH-5 from `docs/todo.md` as fully shipped. **Directional call:** I treated "ship a QA.md entry documenting the verified matrix" (the prompt's explicit fallback) as the deliverable rather than manufacturing a code change, because the audit found nothing broken — the cited concrete spot (Edit Profile avatar-picker `patchMe` error surfacing) is already clean: `EditProfileViewModel.failureMessageOrNull()` maps `NotSignedIn`/`NetworkError` to the connection-error string, name edits surface inline, avatar-only edits navigate optimistically then snackbar the failure. The rejected alternative (force a redundant refactor to "show work") would add risk for no behavior change.

**Findings per surface (all compliant):**
- Home / Profile / Shop catalog / My Items — reads render from cache; no network block.
- Shop money path — `DefaultPurchaseChipPackUseCase` hard-gates on `AuthState.Authenticated` (anonymous → `ClaimAccountRequired`, no session → `NotSignedIn`); `ShopFeatureEntryPoint.showPurchaseSnackbar` surfaces each as an error snackbar.
- Edit Profile / My Items equip — server-mutating but offline-first: optimistic local write + best-effort `sync()`; name-edit failures surface inline, avatar/equip failures snackbar. Matches the write-through state-authority model.
- Claim account — every link/sign-up branch (email + OAuth) maps to `ClaimAccountError.NetworkError`.
- Delete account — `NetworkError` surfaces on the screen; `NotSignedIn` treated as success (data unreferenceable).
- Multiplayer (Lobby) — connection-error recolor already landed under AUTH-6.

**Reviewer notes:** Docs-only commit; no code touched. The matrix is a human device-checklist entry, so it's not unit-testable. If you'd rather the AUTH-5 verification live as a sub-bullet on `ONB-10` than its own section + ID, that's a cheap reshape — I gave it a dedicated `AUTH-5` ID + "Offline gating" section so the gating *rule* (not just one offline cold-boot flow) has a home, since the rule spans surfaces ONB-10 doesn't walk.

**Deferred:** None. The other todo items the cycle context flagged (PROG-1/AUTH-2 money+schema, MP-2 Robolectric infra, ENG-2 version-blocked, BILL-1 money, GAME-3 + AUTH-1 placement Studio-gated) are all genuinely gated — no confident additional pick this cycle.
