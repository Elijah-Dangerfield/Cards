# In-flight

## feat(onboarding): step-progress chip + OS-back stepping

**Problem:** Onboarding had no sense-of-place indicator and no `BackHandler`, so a hardware/gesture back exited the flow instead of returning to the prior step.
**Approach:** Added a "Step N of 3" `StatusPill` (reused the DS pill rather than a one-off box) aligned `TopCenter` in the host `OnboardingScreen` so it overlays every step uniformly, and a `BackHandler` enabled on every step except `Welcome` that routes to `OnboardingAction.Back` — mirroring the in-UI Back button (the VM's `handleBack` already steps HowItWorks→PickIdentity→Welcome and no-ops on Welcome). Welcome stays the only exit point.
**Reviewer notes:** The chip uses `surfaceHigh` + `contentSecondary` at `Label.L300` and sits on the same top line as the existing Back/Skip ghost buttons via `D300` top padding — vertical centering vs. those buttons wasn't eyeballed in Studio (no rendering env here), so a small baseline nudge may be wanted on a visual pass. `BackHandler` emits a "use NavigationEventHandler instead" deprecation warning, consistent with existing usage in `PlayPokerScreen`/`BlockingErrorScreen` — left as-is to match the codebase pattern. No new tests (screen is UI-only; existing `@Preview`s now render the chip on all three steps).
