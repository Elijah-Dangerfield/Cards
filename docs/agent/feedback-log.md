# Feedback log

Append-only record of in-app user feedback already triaged by the
`feedback-triage` skill. The skill checks this file before processing so a
nightly run never re-triages the same report. One line per handled feedback:

```
- <date> · <event_id> · session <session_id> · <disposition> · <Sentry issue URL> · case docs/agent/feedback-cases/<event_id>.md
```

where `disposition` is `todo: <title>`, `backlog`, or `no-action: <reason>`.
The `case …` tail points to the per-report investigation notes (bundle of IDs,
log excerpts, client state, server activity, theory). It's omitted only for
owner change-requests, which are filed as directives without a case file.

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

<!-- 2026-06-24 owner playtest (1 report, session 89cec431, dev-ios-debug; carrier CARDS-2E + feedback-twin CARDS-2F) -->
- 2026-06-24 · c42c260a1df7455c9a2e1eef990c6643 · session 89cec431-6e3e-42e1-ab8f-a8613d1f322e · todo: [P2] redesign the "couldn't create a room" error screen (LobbyScreen CreateNetworkError/CreateUnknownError) · https://elijah-dangerfield.sentry.io/issues/CARDS-2E

<!-- 2026-06-24 feedback-twin sweep (35 reports). These are the Sentry User-Feedback objects (issue.category:feedback) paired with carrier events already triaged in the 2026-06-22/06-23 batches above; each links to its carrier by associated_event_id. No new todos filed (idempotent — dispositions already exist); resolved each twin in Sentry. Keyed by the twin's own feedback event_id. -->
- 2026-06-24 · 9ceee1d0e3594810804558965d719e6e · session 2f21f0e2-cef3-448c-ab03-bf52cfcb8293 · no-action: twin of carrier 4c58fe7bebff49b9a8238db86e584497 (already no-action: dup of Add friend affordance todo) · https://elijah-dangerfield.sentry.io/issues/CARDS-2D
- 2026-06-24 · bda60a8db1f3495485eb2465075c4420 · session 2f21f0e2-cef3-448c-ab03-bf52cfcb8293 · no-action: twin of carrier 053d4dd067fd40aaba55f35b702c4710 (already todo: profile stats/style empty state, CARDS-2A) · https://elijah-dangerfield.sentry.io/issues/CARDS-2B
- 2026-06-24 · afdae75d745b4dbab4fc1d51e867253d · session 2f21f0e2-cef3-448c-ab03-bf52cfcb8293 · no-action: twin of carrier 8345f86c46ec40f1adfc4d74a877cf40 (already todo: room-not-found UX, CARDS-28) · https://elijah-dangerfield.sentry.io/issues/CARDS-29
- 2026-06-24 · d7b922d9b57548b380903bd321ee2639 · session 2f21f0e2-cef3-448c-ab03-bf52cfcb8293 · no-action: twin of carrier 8c788dd4084e4dc998a1afd91e604288 (already todo: P0 MP hand-end stall, CARDS-25) · https://elijah-dangerfield.sentry.io/issues/CARDS-27
- 2026-06-24 · 5ae1ce5665d648968e0247d89d516c79 · session c2807e89-cb17-4515-aac8-cc1b1aa248c9 · no-action: twin of carrier 3ae7928552bf43ccbacb693f02f987b9 (already todo: P0 hand-end stall + mid-game joiner not dealt in, CARDS-24) · https://elijah-dangerfield.sentry.io/issues/CARDS-26
- 2026-06-24 · e31caa8a5a1f4d4283d5ac1aa00d0a9d · session c2807e89-cb17-4515-aac8-cc1b1aa248c9 · no-action: twin of carrier 9767509355cc402db50dd504c169121e (already todo: notify dealt-in next hand, CARDS-22) · https://elijah-dangerfield.sentry.io/issues/CARDS-23
- 2026-06-24 · a0c2d5e9e99b4a4a9651b53101d138e4 · session 2f21f0e2-cef3-448c-ab03-bf52cfcb8293 · no-action: twin of carrier 297bb66cf32447a5858d6ccd21faf612 (already todo: real-chip framing + search-copy + bots explainer, CARDS-20) · https://elijah-dangerfield.sentry.io/issues/CARDS-21
- 2026-06-24 · 4d90519d76d94c90bad868c79f3e30ae · session 2f21f0e2-cef3-448c-ab03-bf52cfcb8293 · no-action: twin of carrier 7c8fb1b5511840e48015b760c2264f7a (already todo: leave private game → Home, CARDS-1Y) · https://elijah-dangerfield.sentry.io/issues/CARDS-1Z
- 2026-06-24 · bb67677180964c639b609e76b7a37372 · session eeddea6e-3c9f-4df7-a122-b2bd5a699a7b · no-action: twin of carrier 10ff4ad1e5614360b9b2af9b6de01ee6 (already backlog: Best Hands/Biggest Pots stats, CARDS-1W) · https://elijah-dangerfield.sentry.io/issues/CARDS-1X
- 2026-06-24 · 2e86cbb9b9554c27aa98abd72c62ae3e · session eeddea6e-3c9f-4df7-a122-b2bd5a699a7b · no-action: twin of carrier 0b5a92432c99452391e9a0a444290728 (already todo: P0 account soft-delete, CARDS-1T) · https://elijah-dangerfield.sentry.io/issues/CARDS-1V
- 2026-06-24 · ff0b2a13956f490d9b1516ceb831d220 · session 092e4cb1-8e0e-4612-8f48-7ba2255a19bf · no-action: twin of carrier 5aaf9285517f47fe838e8bda662eb8df (already todo: tappable Javier progress banner, CARDS-1R) · https://elijah-dangerfield.sentry.io/issues/CARDS-1S
- 2026-06-24 · 5f2492b8ada64f1fb67a0eb48a00801d · session eeddea6e-3c9f-4df7-a122-b2bd5a699a7b · no-action: twin of carrier a09ab98b51474c86ad7af4c1dc38c439 (already todo: level pageantry on player card, CARDS-1P) · https://elijah-dangerfield.sentry.io/issues/CARDS-1Q
- 2026-06-24 · f1647401a7064759b3d7d4bca1e62349 · session ce98ee29-3fe5-4885-9e57-03334c92c4e6 · no-action: twin of carrier 99dcc7b246d840a18e17822fe9854c8b (already no-action: dup of Friend requests inbox — friend graph unbuilt, CARDS-1M) · https://elijah-dangerfield.sentry.io/issues/CARDS-1N
- 2026-06-24 · 254a41435f4b4d94b569e9c7ab3f9ec3 · session ce98ee29-3fe5-4885-9e57-03334c92c4e6 · no-action: twin of carrier 63e8b869e9ec46c59b8b4f28104f5834 (already todo: P0 hand-end stall on all-in/showdown, CARDS-1J) · https://elijah-dangerfield.sentry.io/issues/CARDS-1K
- 2026-06-24 · 3a0d6e76197849f7a24b65073cafb553 · session a780694c-c0c2-42e9-b178-5a02a77ee696 · no-action: twin of carrier bafbf567b0fb4801ab704b4dc3dc9db4 (already todo: P1 private room join not wallet-gated, CARDS-1G) · https://elijah-dangerfield.sentry.io/issues/CARDS-1H
- 2026-06-24 · e57513d0671d45e8abd89aadef052bb0 · session ce98ee29-3fe5-4885-9e57-03334c92c4e6 · no-action: twin of carrier dad50c6cbda5428a96bf234ef9950b2f (already todo: P1 no notice when non-last opponent leaves, CARDS-1E) · https://elijah-dangerfield.sentry.io/issues/CARDS-1F
- 2026-06-24 · 2118247d86c24cbbb3bf4aee9ada67d1 · session c725d44f-aa42-4144-b005-2b8cbdd20d2f · no-action: twin of carrier 1ed853e172834da2813ab829906e89c0 (already todo: P2 real-player bust showed fake-chips copy, CARDS-1C) · https://elijah-dangerfield.sentry.io/issues/CARDS-1D
- 2026-06-24 · 9e003ce6976346b3b253f153a1e4d5d9 · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · no-action: twin of carrier 564b49c4302a4ffcb9f98a76c9027213 (already backlog: more achievements + pacing rebalance, CARDS-1A) · https://elijah-dangerfield.sentry.io/issues/CARDS-1B
- 2026-06-24 · 4909a182e0664880b6707941398be7f1 · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · no-action: twin of carrier 0bfb06842f604fb3b55c92dfde4c63c6 (already todo: remove sunset table theme, CARDS-18) · https://elijah-dangerfield.sentry.io/issues/CARDS-19
- 2026-06-24 · bf736b3d12ab46488baf4fa1daf561c5 · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · no-action: twin of carrier 92024e7293ad4ded8ece14f412201d22 (already todo: P0 bot-occupied MP room stalls at hand end, CARDS-16) · https://elijah-dangerfield.sentry.io/issues/CARDS-17
- 2026-06-24 · 45f45952c2924d28aa27c4de6f9f56e7 · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · no-action: twin of carrier 018ff7b6ec9444e6945e39e14c948179 (already todo: bot-only MP explainer mention real chips not at stake, CARDS-14) · https://elijah-dangerfield.sentry.io/issues/CARDS-15
- 2026-06-24 · 12e0e0bd7fe247ea9a293bcdd7c0341a · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · no-action: twin of carrier 882a1d31506645ae9e664e6b120bee58 (already todo: back out of MP game → Home, CARDS-12) · https://elijah-dangerfield.sentry.io/issues/CARDS-13
- 2026-06-24 · 35fcca50710d48d69b36686df675bc47 · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · no-action: twin of carrier ca964f152912453988cc4d9c0391bc24 (already todo: integration tests play full multi-hand games, CARDS-10) · https://elijah-dangerfield.sentry.io/issues/CARDS-11
- 2026-06-24 · 1ea01068405849d987f7cc5b9acb2114 · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · no-action: twin of carrier 77c87888806b40bdb2af7cd2c83778ae (already todo: debug feedback swipe unreliable in scroll views, CARDS-Y) · https://elijah-dangerfield.sentry.io/issues/CARDS-Z
- 2026-06-24 · 8d4aff3fdfb0446da0e15d7241102003 · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · no-action: twin of carrier 02320f7b46164931994c65148ea27904 (already todo: "Took a seat" shouldn't count bots-only, CARDS-W) · https://elijah-dangerfield.sentry.io/issues/CARDS-X
- 2026-06-24 · ceb2ad95a1fc455580cef32881ae24e2 · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · no-action: twin of carrier e978efb6adc94fa48a93ff6aa060edcd (already no-action: dup of CARDS-16 bot-room hand-end stall, CARDS-T) · https://elijah-dangerfield.sentry.io/issues/CARDS-V
- 2026-06-24 · 7d343a95c3bf4d6f9820a96ae5e72810 · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · no-action: twin of carrier 80f318687bc241dba3bf7e04b8f8c535 (already todo: align chip counter Home/Shop, CARDS-R) · https://elijah-dangerfield.sentry.io/issues/CARDS-S
- 2026-06-24 · 160c39c5e3d744e19bd2dfa78a3df809 · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · no-action: twin of carrier ed8c2179715e446c9080641c86f5b9b0 (already todo: more stats page metrics, CARDS-P) · https://elijah-dangerfield.sentry.io/issues/CARDS-Q
- 2026-06-24 · 2a3dfc3c6d734864958b9ff5ee96f292 · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · no-action: twin of carrier fd2d9c45a1fc4c2baf8814f5a617a20d (already todo: achievements progress ring, CARDS-M) · https://elijah-dangerfield.sentry.io/issues/CARDS-N
- 2026-06-24 · 444c5e2a45ac4fd1ab2e4544aa25f1c7 · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · no-action: twin of carrier 257fecbea6d74050a182e0c252b1db7f (already backlog: backend-backed player style, CARDS-J) · https://elijah-dangerfield.sentry.io/issues/CARDS-K
- 2026-06-24 · 332a924c218c4ebab7c75399761d5000 · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · no-action: twin of carrier 28e42dd57dbd4f5aacbfe6ee7b471a28 (already todo: drop yellow border on equipped items, CARDS-G) · https://elijah-dangerfield.sentry.io/issues/CARDS-H
- 2026-06-24 · 0cf52caa6ef1450ea113190ab1c15167 · session 0c149c11-254b-4a34-bdc1-5c07775702f7 · no-action: twin of carrier ee555b35f40041d7832deed21d7a9c66 (already todo: hide Recently played shelf when empty, CARDS-E) · https://elijah-dangerfield.sentry.io/issues/CARDS-F
- 2026-06-24 · d825af851d9c4be2b95ac626a5371a84 · session afe5d2b7-0f87-4c22-8bd7-20d1e15d7ba0 · no-action: twin of carrier 9ba69ee9fb134ccfbde0d6117362f683 (already todo: confirm before leaving find-a-table lobby, CARDS-C) · https://elijah-dangerfield.sentry.io/issues/CARDS-D
- 2026-06-24 · 1661e9babd594bd99e1ad2215b6e3870 · session afe5d2b7-0f87-4c22-8bd7-20d1e15d7ba0 · no-action: twin of carrier a104a1b6a3b54119949bb964f108241c (already todo: "New here" card close button white/right, CARDS-A) · https://elijah-dangerfield.sentry.io/issues/CARDS-B
- 2026-06-24 · 9b4b6098891249d6b8d7e715fad9dd45 · session afe5d2b7-0f87-4c22-8bd7-20d1e15d7ba0 · no-action: twin of carrier fe5f574dde3f4863897b16bf1434d9c2 (already todo: raise in-app feedback character limit, CARDS-8) · https://elijah-dangerfield.sentry.io/issues/CARDS-9
