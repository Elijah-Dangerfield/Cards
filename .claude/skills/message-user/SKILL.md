---
name: message-user
description: Send an in-app message (dialog or inbox) to a specific Downcard player, by display name or user id. Resolves the name to a user id, drafts copy in the house voice, shows the owner the exact payload, and only fires the admin GitHub Action after they say yes. Use for "message the top shark", "send X an apology/thank-you", "tell that stuck user we fixed it". Never sends without explicit approval.
---

# Message a user

You are writing to **one real person** who opened a poker app, not to a row in a table. Everything here optimises for that: get the right person, say something worth reading, and never send anything the owner hasn't seen first.

## The one hard rule

**Never fire the workflow without the owner's explicit go-ahead in this conversation.** Show the resolved recipient and the exact copy, then wait. "Queue one up for the top shark" is permission to *draft*, not to send. A message lands as a modal on a stranger's phone and cannot be recalled.

If the owner has already said "send it" to a specific drafted message, that approval covers that message only. A second message needs a second yes.

## Fixed coordinates

- **Workflows:** `admin-send-message-prod.yml` (real users) and `admin-send-message.yml` (dev). Prod requires `confirm: PROD`.
- **Addressing key is the user id (UUID)**, never the display name. `profiles.display_name` is unique but *editable*, so a rename between lookup and send would hit the wrong person. Resolve the name to an id, show both, send the id.
- **Grafana Postgres prod** datasource `ffrewas5byf40d`, dev `dfrex4f7bg7b4b`. Read-only.
- **`dc-gameplay` → "Shark leaderboard"** already renders `display_name` + `user_id` for players with 25+ hands. Fastest lookup when the owner names a top player.
- **Log:** append to `docs/agent/messages-log.md` after every send.

## Procedure

### 1. Work out who

If given a user id, verify it exists and get the display name so the owner can sanity-check the human:

```sql
select display_name, user_id::text, created_at::text from profiles where user_id = '<uuid>'
```

If given a name or a description ("the top shark", "our heaviest player"), resolve it:

```sql
select p.display_name, s.user_id::text, s.hands_played,
       round(100.0*s.hands_won/nullif(s.hands_played,0),1) as win_pct
from user_player_stats s join profiles p using (user_id)
where p.display_name ilike '<name>'
```

Drop the `where` and order by `hands_played desc` for "top player" style asks. **If more than one row matches, stop and ask** — do not guess which human the owner meant. Display names have collided before via renames.

Sanity-check the person before writing. Are they mid-incident? Did they just get hit by a bug you know about? Query the relevant ledger. Messaging your most engaged player a cheerful note while their XP has silently been broken for ten days is worse than not messaging them.

### 2. Pick the surface

- **`dialog`** — a modal on next foreground. Interrupts. Use for something the player genuinely needs to see: an apology, a make-good, a direct question.
- **`inbox`** — a passive entry plus a badge. Use for anything they can read whenever, and for anything that would be rude to interrupt a hand with.

Default to `inbox` unless the message is time-sensitive or personal. When in doubt, recommend `inbox` and say why.

### 3. Write it

Apply the `unslop-text` principles and the house marketing voice: warm, simple, sincere, "make poker fun again". Not cocky, not punchy-for-the-sake-of-it, no em dashes. See `docs/marketing-copy.md`.

Hard limits the workflow enforces: **title ≤ 80 chars, body ≤ 500 chars.** Count them before proposing, don't make the owner discover it in a failed run.

Write like the founder, because that's who it is:

- Say the specific thing. "You've played more hands than anyone else on Downcard" beats "Thanks for being a valued player."
- If you're apologising, name what broke in plain language and say what you did about it. Don't explain the architecture.
- One ask at most, and only if you actually want an answer.
- No fake urgency, no growth-hack phrasing, no exclamation-mark padding.
- `emoji` is a single optional accent, not decoration.

If the message offers something (chips, a thank-you grant), that is a **separate** action via `admin-grant-chips-prod.yml`. Don't promise chips in copy the owner hasn't agreed to grant, and don't grant them yourself off the back of a message.

### 4. Show the owner, then stop

Present exactly this, and wait:

```
To:      <display_name>  (<user_id>)
Context: <one line: why this person, what state they're in>
Surface: dialog | inbox        Expires: <N> days
Emoji:   <emoji or none>       Deep link: <link or none>

Title (<n>/80):
  <title>

Body (<n>/500):
  <body>
```

Offer one alternative phrasing if the tone is a judgement call. Then ask whether to send. **Do not run the workflow in the same turn as showing the draft.**

### 5. Send

Only after an explicit yes. Prod:

```bash
gh workflow run admin-send-message-prod.yml \
  -f confirm=PROD \
  -f userId='<uuid>' \
  -f kind='<dialog|inbox>' \
  -f title='<title>' \
  -f body='<body>' \
  -f emoji='<emoji>' \
  -f deepLink='' \
  -f expiresInDays='30' \
  -f idempotencyKey='<stable-key>'
```

Always pass an `idempotencyKey` — something stable and descriptive like `thanks-sofiacab-2026-08-31`. It's what stops a re-run from double-messaging someone if you're unsure whether the first attempt landed. Never re-run without one.

Use `admin-send-message.yml` (no `confirm`) for a dev dry run when the copy is long or the deep link is untested.

### 6. Verify and log

Watch the run and read its summary, which reports the HTTP status:

```bash
gh run watch $(gh run list --workflow=admin-send-message-prod.yml --limit 1 --json databaseId --jq '.[0].databaseId') --exit-status
```

A non-2xx means nothing was delivered. Report the real status rather than assuming it worked.

Then append one line to `docs/agent/messages-log.md` (create it if missing):

```
- <date> · <display_name> (<user_id>) · <dialog|inbox> · "<title>" · <why> · run <url> · HTTP <status>
```

## Guardrails

- **One send per approval.** Approval doesn't generalise across messages, users, or days.
- **Never bulk-send.** This skill addresses one person. If the owner asks for many, stop and say that needs a real campaign path with an opt-out, not a loop over this workflow.
- **Nothing sensitive in the copy.** No user ids, no internal error names, no Sentry links, no other player's name or stats.
- **Don't invent facts about their account.** If you say "you've played 1,258 hands", that number comes from a query you ran in this session.
- **Read-only on Postgres.** Look users up; never write.
- **Prod vs dev:** `confirm: PROD` is the only thing separating a real human from a test. Never default it in, always type it deliberately.
