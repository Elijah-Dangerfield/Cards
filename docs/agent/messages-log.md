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
