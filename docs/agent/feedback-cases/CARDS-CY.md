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
