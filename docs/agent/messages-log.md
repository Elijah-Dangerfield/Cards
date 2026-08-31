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

<!-- No messages sent yet. -->
