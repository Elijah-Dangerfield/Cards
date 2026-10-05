# Player messages log

Every in-app message sent to a named player through `admin-send-message-prod.yml`
(or its dev twin), one line per send. Written by the [`message-user`](../../.claude/skills/message-user/SKILL.md)
skill after the run reports its HTTP status.

The point is that nobody has to guess whether a player already heard from us, and
that a re-send is always a deliberate decision rather than an accident. Messages
carry an `idempotencyKey`, so a repeat of a key already listed here is a no-op
server-side.

Format:

```
- <date> · <display_name> (<user_id>) · <dialog|inbox> · "<title>" · <why> · run <url> · HTTP <status>
```

---
- 2026-08-31 · SofiaCab (d212d310-5158-46a7-8f75-a20369f7d0e5) · dialog · "Handwritten Message" · founder reaching out to the most active player (1,258 hands) for pre-iOS feedback · run https://github.com/Elijah-Dangerfield/Cards/actions/runs/33426369872 · HTTP 2xx (row confirmed in user_messages)
- 2026-08-31 · Red John (9742a1e4-4e2c-4f27-bf66-b4abdbdc8137) · dialog · "Handwritten Message" · founder asking an early player who lapsed after 08-23 for an honest take before the iOS release · run https://github.com/Elijah-Dangerfield/Cards/actions/runs/33426500274 · HTTP 2xx (row confirmed in user_messages)
- 2026-09-01 · Ilucha (d65953cc-93b6-4229-a21f-861568a8c44e) · dialog · "Handwritten Message" · day-old account already at 561 hands; asked about onboarding while it is fresh, before the iOS release · run https://github.com/Elijah-Dangerfield/Cards/actions/runs/33527393240 · HTTP 2xx (row confirmed in user_messages)
- 2026-10-05 · Kris Tina (6dc67537-ef83-4fbd-9677-59ff57efaa50) · dialog · "Handwritten Message" · most hands on Downcard (1,489 since 09-25); asked what keeps them coming back · run https://github.com/Elijah-Dangerfield/Cards/actions/runs/37245932533 · HTTP 200 (row confirmed in user_messages)
- 2026-10-05 · Tiramisu (fb7e0800-d33e-4f50-9fcd-03d83e8ff01b) · dialog · "Handwritten Message" · 301 of 343 hands at multiplayer tables since 09-25; asked how playing real people feels · run https://github.com/Elijah-Dangerfield/Cards/actions/runs/37245938335 · HTTP 200 (row confirmed in user_messages)
- 2026-10-05 · QuickTen29 (b24192ae-c268-4996-938e-0abe8419f764) · dialog · "Handwritten Message" · 490 hands, all against bots; asked what they like about bots and what would get them to a real table · run https://github.com/Elijah-Dangerfield/Cards/actions/runs/37245944607 · HTTP 200 (row confirmed in user_messages)
