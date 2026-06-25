# In-flight

## feat(rooms): confirm credited stack on leaving a real-chip table (MP-6)

**Problem:** A player who wins on a real-chip / subsidized MP table and leaves
gets their seat stack cashed back to the wallet server-side, but the client only
learns the new number on the next sync — the balance bump reads as an
unexplained glitch (Sentry CARDS-2N / 2Y). The leave path had the data after
`chipsRepository.sync()` but never surfaced it.

**Approach:** `PlayPokerViewModel.leaveAndReconcileWallet` already syncs the
wallet on an MP leave (MP-7). It now captures the balance before/after that sync
and, on a real gain, hands the delta + new balance to a new injected
`LeaveCashOutNotifier` seam. The default impl composes the localized message and
fires the global `showSnackBar` (host-agnostic, so it survives this VM's
teardown and lands on Home; `delayBy` lets that host mount first). I pulled the
rendering into a DI seam rather than inlining it because the message uses
`getString` — a suspend resource read that throws in Android JVM unit tests
("Resources not mocked") — so inlining would have made the credited-amount
decision untestable. The VM keeps the testable decision; a fake notifier records
the call. Rejected alternative: emitting a `PlayPokerEvent` for the entry point
to toast — the leave fires `onBack()` immediately after `LeaveTable`, tearing
down the entry-point event collector before the async sync resolves, so that
path is dead by the time the credited amount is known.

**Reviewer notes:** The 600ms `delayBy` in `SnackbarLeaveCashOutNotifier` is a
judgement call to clear the leave→Home nav transition before the toast presents;
worth an eyeball on-device that it lands on Home and not mid-transition. The
credited amount is derived from the local balance delta around the sync (not a
server-reported `refunded` field), which is correct as long as no other wallet
mutation races the leave sync — the single-flight mutex on `sync()` plus the
fact that leaving tears down the table makes a concurrent credit unlikely, but
it's a delta, not an authoritative line item.
