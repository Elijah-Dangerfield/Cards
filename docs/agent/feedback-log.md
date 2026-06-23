# Feedback log

Append-only record of in-app user feedback already triaged by the
`feedback-triage` skill. The skill checks this file before processing so a
nightly run never re-triages the same report. One line per handled feedback:

```
- <date> · <event_id> · session <session_id> · <disposition> · <Sentry issue URL>
```

where `disposition` is `todo: <title>`, `backlog`, or `no-action: <reason>`.

---

- 2026-06-22 · CARDS-5 (issue 7567845451) · no-action: owner test + spam feedback, resolved in bulk while validating the triage loop · https://elijah-dangerfield.sentry.io/issues/CARDS-5

<!-- 2026-06-22 owner playtest batch (18 reports, one session 0c149c11-254b-4a34-bdc1-5c07775702f7, dev-ios-debug build) -->
- 2026-06-22 · fe5f574dde3f4863897b16bf1434d9c2 · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · todo: Raise the in-app feedback character limit · https://elijah-dangerfield.sentry.io/issues/CARDS-8
- 2026-06-22 · a104a1b6a3b54119949bb964f108241c · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · todo: "New here" home card — white close button on the right · https://elijah-dangerfield.sentry.io/issues/CARDS-A
- 2026-06-22 · 9ba69ee9fb134ccfbde0d6117362f683 · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · todo: Confirm before leaving find-a-table/bots lobby via back · https://elijah-dangerfield.sentry.io/issues/CARDS-C
- 2026-06-22 · ee555b35f40041d7832deed21d7a9c66 · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · todo: Hide "Recently played with" shelf when empty · https://elijah-dangerfield.sentry.io/issues/CARDS-E
- 2026-06-22 · 28e42dd57dbd4f5aacbfe6ee7b471a28 · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · todo: Drop yellow border on equipped items (keep badge) · https://elijah-dangerfield.sentry.io/issues/CARDS-G
- 2026-06-22 · 257fecbea6d74050a182e0c252b1db7f · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · backlog: Player-style backend-backed so opponents can see it · https://elijah-dangerfield.sentry.io/issues/CARDS-J
- 2026-06-22 · fd2d9c45a1fc4c2baf8814f5a617a20d · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · todo: Achievements progress ring around the medallion · https://elijah-dangerfield.sentry.io/issues/CARDS-M
- 2026-06-22 · ed8c2179715e446c9080641c86f5b9b0 · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · todo: More on the stats page (win/loss ratio, players played with) · https://elijah-dangerfield.sentry.io/issues/CARDS-P
- 2026-06-22 · 80f318687bc241dba3bf7e04b8f8c535 · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · todo: Align chip counter shared between Home and Shop · https://elijah-dangerfield.sentry.io/issues/CARDS-R
- 2026-06-22 · e978efb6adc94fa48a93ff6aa060edcd · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · no-action: duplicate of CARDS-16 (bot-room hand-end stall) · https://elijah-dangerfield.sentry.io/issues/CARDS-T
- 2026-06-22 · 02320f7b46164931994c65148ea27904 · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · todo: "Took a seat" shouldn't count bots-only · https://elijah-dangerfield.sentry.io/issues/CARDS-W
- 2026-06-22 · 77c87888806b40bdb2af7cd2c83778ae · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · todo: Debug feedback swipe unreliable in scroll views · https://elijah-dangerfield.sentry.io/issues/CARDS-Y
- 2026-06-22 · ca964f152912453988cc4d9c0391bc24 · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · todo: Integration tests should play full multi-hand games · https://elijah-dangerfield.sentry.io/issues/CARDS-10
- 2026-06-22 · 882a1d31506645ae9e664e6b120bee58 · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · todo: Back out of MP game should go Home, not lobby · https://elijah-dangerfield.sentry.io/issues/CARDS-12
- 2026-06-22 · 018ff7b6ec9444e6945e39e14c948179 · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · todo: Bot-only MP explainer should mention real chips not at stake · https://elijah-dangerfield.sentry.io/issues/CARDS-14
- 2026-06-22 · 92024e7293ad4ded8ece14f412201d22 · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · todo: [P0] Bot-occupied MP room stalls at hand end (root cause in trace 44744008b5672640a64d0849c14384bf, room A3DTHY) · https://elijah-dangerfield.sentry.io/issues/CARDS-16
- 2026-06-22 · 0bfb06842f604fb3b55c92dfde4c63c6 · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · todo: Remove the "sunset" table theme · https://elijah-dangerfield.sentry.io/issues/CARDS-18
- 2026-06-22 · 564b49c4302a4ffcb9f98a76c9027213 · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · backlog: More achievements + early-stage pacing rebalance · https://elijah-dangerfield.sentry.io/issues/CARDS-1A

<!-- 2026-06-23 owner two-device MP playtest batch (17 reports, sessions 2f21f0e2 / c2807e89 / eeddea6e / ce98ee29 / a780694c) -->
- 2026-06-23 · 8c788dd4084e4dc998a1afd91e604288 · session 2f21f0e2-cef3-448c-ab03-bf52cfcb8293 · todo: [P0] MP hand-end stall in human rooms (B7); confirmed room NMGSSC Hand 4 finished, no Hand 5 · https://elijah-dangerfield.sentry.io/issues/CARDS-25
- 2026-06-23 · 3ae7928552bf43ccbacb693f02f987b9 · session c2807e89-cb17-4515-aac8-cc1b1aa248c9 · todo: [P0] hand-end stall + [P1] mid-game public joiner not dealt in (room NMGSSC) · https://elijah-dangerfield.sentry.io/issues/CARDS-24
- 2026-06-22 · 63e8b869e9ec46c59b8b4f28104f5834 · session ce98ee29-3fe5-4885-9e57-03334c92c4e6 · todo: [P0] hand-end stall on all-in/showdown, both devices stuck (room H5QCRW; backend local-only, no cloud logs) · https://elijah-dangerfield.sentry.io/issues/CARDS-1J
- 2026-06-23 · 0b5a92432c99452391e9a0a444290728 · session eeddea6e-3c9f-4df7-a122-b2bd5a699a7b · todo: [P0] account deletion is a soft-delete — user can still sign in (HttpSupabaseAdminClient.deleteUser) · https://elijah-dangerfield.sentry.io/issues/CARDS-1T
- 2026-06-22 · bafbf567b0fb4801ab704b4dc3dc9db4 · session a780694c-c0c2-42e9-b178-5a02a77ee696 · todo: [P1] private room join not gated on wallet balance (room 5BB2UJ; guard only on find path) · https://elijah-dangerfield.sentry.io/issues/CARDS-1G
- 2026-06-22 · dad50c6cbda5428a96bf234ef9950b2f · session ce98ee29-3fe5-4885-9e57-03334c92c4e6 · todo: [P1] no at-table notice when a non-last opponent leaves mid-game (MemberLeft dropped) · https://elijah-dangerfield.sentry.io/issues/CARDS-1E
- 2026-06-23 · 7c8fb1b5511840e48015b760c2264f7a · session 2f21f0e2-cef3-448c-ab03-bf52cfcb8293 · todo: [P1] leaving a private MP game lands in lobby, should go Home · https://elijah-dangerfield.sentry.io/issues/CARDS-1Y
- 2026-06-23 · 8345f86c46ec40f1adfc4d74a877cf40 · session 2f21f0e2-cef3-448c-ab03-bf52cfcb8293 · todo: [P2] better "room not found" UX — keep user on input screen · https://elijah-dangerfield.sentry.io/issues/CARDS-28
- 2026-06-23 · 9767509355cc402db50dd504c169121e · session c2807e89-cb17-4515-aac8-cc1b1aa248c9 · todo: [P2] tell user they'll be dealt in next hand when joining a live game · https://elijah-dangerfield.sentry.io/issues/CARDS-22
- 2026-06-23 · 297bb66cf32447a5858d6ccd21faf612 · session 2f21f0e2-cef3-448c-ab03-bf52cfcb8293 · todo: [P2] real-chip table fake-chip framing + slow search-copy rollover + tighten bots-for-real-money explainer · https://elijah-dangerfield.sentry.io/issues/CARDS-20
- 2026-06-22 · 1ed853e172834da2813ab829906e89c0 · session ce98ee29-3fe5-4885-9e57-03334c92c4e6 · todo: [P2] real-player bust showed "fake chips against bots" copy (folded into the real-chip framing todo) · https://elijah-dangerfield.sentry.io/issues/CARDS-1C
- 2026-06-23 · 053d4dd067fd40aaba55f35b702c4710 · session 2f21f0e2-cef3-448c-ab03-bf52cfcb8293 · todo: [P2] profile stats/style should show "play more hands" empty state, not fake "Sharp and Steady" · https://elijah-dangerfield.sentry.io/issues/CARDS-2A
- 2026-06-22 · 5aaf9285517f47fe838e8bda662eb8df · session (BoldQueen41) · todo: [P2] make whole "Javier progress" profile banner tappable · https://elijah-dangerfield.sentry.io/issues/CARDS-1R
- 2026-06-22 · a09ab98b51474c86ad7af4c1dc38c439 · session ce98ee29-3fe5-4885-9e57-03334c92c4e6 · todo: [P2] render level with more pageantry on outward player card · https://elijah-dangerfield.sentry.io/issues/CARDS-1P
- 2026-06-23 · 10ff4ad1e5614360b9b2af9b6de01ee6 · session eeddea6e-3c9f-4df7-a122-b2bd5a699a7b · backlog: more stats metrics (Best Hands / Biggest Pots) · https://elijah-dangerfield.sentry.io/issues/CARDS-1W
- 2026-06-23 · 4c58fe7bebff49b9a8238db86e584497 · session 2f21f0e2-cef3-448c-ab03-bf52cfcb8293 · no-action: duplicate of existing "Tap-an-opponent sheet — Add friend affordance" todo (friend request/status on player card) · https://elijah-dangerfield.sentry.io/issues/CARDS-2C
- 2026-06-22 · 99dcc7b246d840a18e17822fe9854c8b · session ce98ee29-3fe5-4885-9e57-03334c92c4e6 · no-action: duplicate of "Friend requests inbox" todo — friend graph is unbuilt/no-op, so requests don't deliver yet · https://elijah-dangerfield.sentry.io/issues/CARDS-1M
