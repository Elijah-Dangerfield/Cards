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
