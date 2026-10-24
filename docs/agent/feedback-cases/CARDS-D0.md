# CARDS-D0 — in-app review prompt throws with no foreground Activity

**Signal:** Sentry, no user feedback. 1 event 2026-10-23, `cards@0.6.0+1308`, Android 16,
`store-android-release`, session `9877d353`, route `PlayMultiplayerRoute`. Todo: **ENG-85 [P2]**.

## What is proven

`IllegalStateException: No foreground Activity available for in-app review.` thrown at
`AndroidReviewLauncher.kt:39`, called from `RealReviewPromptCoordinator.requestPrompt:82` inside a
coroutine. The line is an explicit `error()` on a null `activityProvider.currentActivity()`.
Breadcrumbs put it immediately after `conn.recovered`, two achievements (`POT_1000`, `POT_5000`) and
two `achievement.celebration_shown`, and the error appears **twice**.

## Reading

The prompt is fired off a coroutine at a celebration moment. No Activity was current at that
instant. `Catching` contains it, so there is no crash, but the ask is lost at the point it was best
timed, and it logs at ERROR for a condition that is expected rather than exceptional.

The double fire suggests the coordinator does not dedupe a failed attempt.

## Fix shape

A missing Activity wants a quiet null-return, not `error()`. Consider whether the coordinator should
re-arm so the prompt survives to the next foreground rather than being spent.
