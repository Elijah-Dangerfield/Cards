# CARDS-CY — iOS refuses to open the problem-gambling link

**Signal:** Sentry, no user feedback. 1 event 2026-10-22, `cards@0.6.0+1308`, iOS 27.0.1,
iPhone12,5, `store-ios-release`, session `e16b1e6c`, route `ShopProductSheetRoute`.
Message: `iOS refused to open https://www.ncpgambling.org/`. Todo: **ENG-83 [P1]**.

## What is proven

The log line is ours and fires on the failure path of the iOS web-link launcher. Preceding
breadcrumbs are `room.left`, `shop.viewed`, two `http`, `navigation` — an ordinary browse into the
shop sheet, then the tap.

## Why one event is enough

This is deterministic, not incidental. A link either opens or it does not, and nothing about this
user's state is unusual. One event means one person tried it, not that it fails one time in many.
The same landmine is recorded in this codebase's lineage: `canOpenURL` silently blocks outbound
links for undeclared schemes.

## Why it matters beyond the tap

Problem-gambling resources are the kind of thing an App Store reviewer looks for in a poker app, and
a dead link to the national council is worse than no link.

## Fix shape

`Router.openWebLink` → the iOS `WebLinkLauncher`. Check whether `canOpenURL` is consulted at all for
an `https` URL (it does not need to be) and whether `LSApplicationQueriesSchemes` is declared. The
OS declining should not log at ERROR.

## What the fix found: the `canOpenURL` theory was wrong

`canOpenURL` was already gone, removed in `ca77c1af` (2026-09-03) for exactly the reason the lineage
landmine describes, and nothing re-introduced it: `IosWebLinkLauncher` opens unconditionally, and
`apps/ios/iosApp/Info.plist` declares no `LSApplicationQueriesSchemes` at all. So `0.6.0` had no
gate. The ERROR line is the honest refusal path, not a self-inflicted one: `openURL` ran, and the
system itself came back `false`.

That matters because the two problems are different. A gate is ours to delete. A refusal is iOS
saying no other app would take the URL, and **why** it said no is not in this event. A restricted
device (Screen Time, Content & Privacy, web content), a wedged or unavailable default browser, or
the app not being active when the call landed all look identical from here. One event with no device
in hand cannot separate them.

So the fix stops depending on the answer. `openURL` still goes first, because it is what sends an
`apps.apple.com` link to the App Store app rather than a web view of the listing, and what lets
`cards://` and `mailto:` reach their handlers. When it refuses an http(s) URL, the launcher now
presents `SFSafariViewController` instead, which needs no other app's cooperation. The log drops to
Warn: `minEventLevel` is Error, so that one level is the whole reason this became a Sentry issue
rather than a breadcrumb.
