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
