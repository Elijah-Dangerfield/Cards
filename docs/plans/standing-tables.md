# Standing tables

**Status:** Approved to build, 2026-11-14. Ships dark behind `standingTables.enabled` (default off). The owner reviews the result and turns it on.
**Todo:** ROOM-24 (umbrella). Related: ENG-89 (route events), SITE-2 (invite preview on the static site), ROOM-23 (universal links).

## What it is

A standing table is a private table that belongs to a group of friends, not to one evening. The host names it ("Thursday Degens"), picks a night and a time ("Thursdays, 8:00 PM"), and gets a six-character code that never expires. Friends tap the invite once, add the table to their calendar, and come back to the same code every week. The calendar entry is the reminder, so the feature needs no push notifications.

### Why this, and why now

- Downcard is used as poker with friends. In the last 30 days 101 players played against a real person, and 68 pairs played together more than once.
- Two strangers are rarely online together (102 public searches in a week, 12 matched with a human). Friends can be, if they plan it.
- A room code dies when the last player leaves. In the last 14 days, 18 joins failed with "code not found", most likely old codes from earlier games. A standing code never dies.
- The decisions log rejected "a scheduled nightly table" on 2026-10-21 because "an appointment you cannot be reminded of is not an appointment". The calendar entry answers that: the phone's own calendar reminds people, with the join link inside the event.
- Every standing table sends a link to people who may not have the app. That's growth that doesn't cost ad money.

### What it is not

- Not a tournament or a league. The table is just a private room that keeps its code, name and schedule.
- No RSVPs, no push, no chat. The calendar entry stands in for the RSVP.
- It doesn't lock anyone out. A standing table can be opened any day at any time; the schedule is for the calendar and for Home ("Tonight, 8:00 PM").

## The experience

1. **Discover.** A one-time sheet on Home introduces standing tables to players who have already played at least one hand. Home's private-room chooser gains a third option.
2. **Create.** The host enters a name, a day, a time, a buy-in and a seat count, then lands on "Your table is set": the code is shown large, with Share invite, Add to calendar and Open table.
3. **Invite.** The share text carries a link whose preview card shows the table's name and schedule, not a generic app icon.
4. **Join.** A friend taps the link. With the app, they land in the lobby for that code. Without it, they get the web page: name, schedule, code, Add to calendar, and the right store button.
5. **Come back.** The calendar event fires. The friend taps the link in the event, or opens Downcard, where "Your tables" on Home shows the table and whether anyone is sitting at it.
6. **Play.** The first person to open the table creates the live room from the saved settings. When everyone leaves, the room goes away as usual and the standing table stays.

## Decisions (settled; do not reopen while building)

| Topic | Decision |
|---|---|
| Name in the product | "Standing table". Plural "standing tables". Never "scheduled room" or "recurring game". |
| Storage | A new `standing_tables` row is the durable thing. The live room is created on demand with the same code, then torn down as today when empty. No room is kept in memory between games. |
| Who can open it | Anyone signed in who has the code, any time. The first opener becomes the host for that sitting, the same as any private room. |
| Old app versions | Must work. A friend on 0.8.0 typing the code into "Join" gets into the table, because the server creates the room inside the existing `POST /v1/rooms/{code}/join`. |
| Regulars | Anyone who has joined the table's room once becomes a regular automatically. "Your tables" shows tables you own or are a regular of. There is no separate "I'm in" button. |
| Limits | Each owner can have at most 3 active standing tables. Name: 1 to 32 characters after trimming. Seats and buy-in use the private-room limits. |
| Schedule shape | One weekly slot: weekday, local time, IANA time zone, duration (default 120 minutes). No biweekly, monthly or multiple days in v1. |
| Deleting | The owner can archive a table. The code stays reserved forever and is never handed out again. Joining an archived table fails with its own error. |
| Reminders | The calendar entry, from a native "add event" screen on both platforms with a weekly repeat. On the web, a `.ics` file and a Google Calendar link. |
| Link previews | Rendered by the Ktor server at `https://join.downcard.app/{CODE}`, with a per-table `og:image` drawn on the server. The static site can't vary previews per code (see "Link previews" below). |
| Share URL | Becomes a remote string, `invite.shareBaseUrl`, default `https://downcard.app/join`. The owner switches it to `https://join.downcard.app` once DNS is live. No app release needed to switch. |
| Gate | Client flag `standingTables.enabled`, default false. Server endpoints are always on (harmless when no client calls them), so the server can ship first. |
| Feature intro | A reusable one-time "new feature" sheet on Home. Standing tables is its first use. |

## Build plan

Nine tasks. The API contract below is final, so client and server tasks can run in parallel against fakes. "Files" lists where the work goes; agents should not need to search for them.

| Task | What | Depends on | Touches |
|---|---|---|---|
| T0 | Route-view events to Loki | none | `apps/compose/.../App.kt`, `docs/wiki/app-events.md` |
| T1 | Server: standing tables, reserved codes, materialize on join | none | `apps/server` (migration, domain, data, routes, DI) |
| T2 | Server: share page, `.ics`, preview image | T1 | `apps/server` (new routes and resources only) |
| T3 | Website: invite preview fix on the static site | none | `website/src/pages/404.astro`, `website/src/pages/join.astro` |
| T4 | Client data layer, flags, share base URL | none (contract below) | `libraries/rooms`, `libraries/rooms/impl`, `features/lobby/.../RoomInvite.kt` |
| T5 | `CalendarLauncher` on both platforms | none | `libraries/navigation`, `libraries/navigation/impl`, `apps/ios`, `apps/compose/src/iosMain` |
| T6 | Client UI: create, detail, Home section, lobby banner | T4, T5 | `features/lobby/impl`, `features/home/impl`, `libraries/ui`, strings |
| T7 | One-time feature intro sheet | T4, and after T6 (both edit Home) | `features/home/impl`, `libraries/cards/.../AppCache.kt`, strings |
| T8 | Docs: QA cases, events, decision, release note | all | `docs/` |

Parallel groups: {T0, T1, T3, T4, T5} first, then {T2, T6}, then T7, then T8. T6 and T7 must not run at the same time (both edit `HomeScreen.kt`, `HomeViewModel.kt` and `HomeFeatureEntryPoint.kt`).

House rules apply throughout (see `AGENTS.md`): Conventional Commits, `Catching {}` never `runCatching`, `DispatcherProvider` never `Dispatchers.*`, all strings in `libraries/resources/.../values/strings.xml` named `{surface}_{role}_{specifier}`, DS components and tokens only, `@Preview` for every screen state (`org.jetbrains.compose.ui.tooling.preview.Preview`, `PreviewContent {}`), routes as `class`/`data class` (never `data object`), `@Serializable` enum route args with a `typeMap`, tests first for anything with logic. Run builds through `~/.claude/bin/q`.

---

## API contract (shared by T1, T2, T4)

All JSON envelopes carry `schemaVersion: 1`, like the existing room DTOs. Errors use the existing shape `{"error":{"code":"…","message":"…"}}`. Times on the wire: `localTime` is `"HH:mm"` (24-hour), `timeZone` is an IANA id (`"America/New_York"`), instants are ISO-8601 UTC strings. Weekday is ISO: 1 = Monday … 7 = Sunday.

### `StandingTableDto`

```json
{
  "schemaVersion": 1,
  "code": "K7M2QX",
  "name": "Thursday Degens",
  "ownerUserId": "uuid",
  "ownerDisplayName": "Maexj",
  "isOwner": true,
  "buyIn": 5000,
  "maxSeats": 6,
  "weekday": 4,
  "localTime": "20:00",
  "timeZone": "America/New_York",
  "durationMinutes": 120,
  "nextStartsAt": "2026-11-20T01:00:00Z",
  "regularCount": 5,
  "live": { "seatedHumans": 3, "status": "playing" },
  "archived": false,
  "feltProductId": null,
  "cardBackProductId": null
}
```

- `nextStartsAt` is the next start at or after `now - durationMinutes`. A table that started 30 minutes ago still reports tonight's start, so Home can say "On now".
- `live` is null when no room with this code is in memory. `status` is `lobby` or `playing`.

### Endpoints (all under `authenticate(SUPABASE_JWT_AUTH)`)

| Method and path | Body | Success | Errors |
|---|---|---|---|
| `POST /v1/standing-tables` | `CreateStandingTableRequest { name, buyIn, maxSeats, weekday, localTime, timeZone, durationMinutes = 120, feltProductId?, cardBackProductId? }` | 201 `StandingTableDto` | 400 `bad_request` (validation; the message names the field), 409 `too_many_standing_tables` |
| `GET /v1/standing-tables/mine` | | 200 `{ schemaVersion, tables: [StandingTableDto] }`, owned first, then by `nextStartsAt` | |
| `GET /v1/standing-tables/{code}` | | 200 `StandingTableDto` | 404 `standing_table_not_found` |
| `PATCH /v1/standing-tables/{code}` | Any subset of the create fields except the cosmetics | 200 `StandingTableDto` | 403 `not_owner`, 404, 400 |
| `DELETE /v1/standing-tables/{code}` | | 204 (archives) | 403 `not_owner`, 404 |
| `DELETE /v1/standing-tables/{code}/regulars/me` | | 204 (removes it from my list) | 404 |

### Changes to existing room endpoints

- `POST /v1/rooms/{code}/join`: when no room with `code` exists and a non-archived standing table does, the server creates the room from the table's settings with that exact code, makes the caller the host and seats them, then responds as today (200 `JoinRoomResponse`, `alreadyJoined = true`). The balance check (`insufficient_balance`) still applies before anything is created. For an archived table, respond 404 with code `standing_table_closed`.
- Joining a room whose code belongs to a standing table also upserts the caller into `standing_table_regulars`.
- `RoomDto` gains `standingTable: StandingTableSummaryDto?` = `{ name, weekday, localTime, timeZone, nextStartsAt }`, null for ordinary rooms. Old clients ignore unknown fields (check that the client's `Json` has `ignoreUnknownKeys = true`; it must already, since the server adds fields regularly).
- `POST /v1/rooms` (ordinary create) must never hand out a code that belongs to a standing table or to a saved room (see T1 step 4).

---

## T0: Route-view events to Loki

**Why:** the owner wants errors, crashes and navigation routes to be enough to see where a player went before leaving. Routes only reach Sentry's scope today, so a player with no errors leaves no route trail in Grafana.

1. In `apps/compose/src/commonMain/kotlin/com/cards/App.kt`, the `LaunchedEffect(currentRouteName)` near lines 419–428 already calls `telemetry.setCurrentRoute(it)`. In the same effect, when the name is non-null and differs from the previous one, call `KLog.withTag("Navigation").logEvent("nav.route_viewed", "route" to name, "previous" to previousName)`. Keep `previousName` in a `remember { mutableStateOf<String?>(null) }`.
2. Route names are class names (`routeClassNameOrNull()`), so they contain no user data. Do not add arguments.
3. Add `nav.route_viewed` to `docs/wiki/app-events.md` under a new `## Navigation` section, using the table format already there.
4. Test: if `App.kt` has no test seam, extract the dedupe into a small pure function (`fun nextRouteEvent(previous: String?, current: String?): Pair<String, String?>?`) with a unit test in `apps/compose/src/commonTest`.

**Done means:** each screen change on a debug build shows one `nav.route_viewed` record in Loki dev; rotation and recomposition don't duplicate it.

---

## T1: Server

Paths are under `apps/server/src/main/kotlin/com/cards/server/` unless noted. Follow the repository, DI and route patterns of `PlayerReportRepository` / `PostgresPlayerReportRepository` and `routes/RoomRoutes.kt`.

### 1. Migration `src/main/resources/db/migration/V92__standing_tables.sql`

Check that V92 is still the next free number first. Start with a header comment like the other migrations.

```sql
CREATE TABLE standing_tables (
    code               TEXT PRIMARY KEY,
    owner_user_id      UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    name               TEXT NOT NULL CHECK (char_length(name) BETWEEN 1 AND 32),
    buy_in             BIGINT NOT NULL,
    max_seats          INT NOT NULL,
    weekday            SMALLINT NOT NULL CHECK (weekday BETWEEN 1 AND 7),
    local_time         TIME NOT NULL,
    time_zone          TEXT NOT NULL,
    duration_minutes   INT NOT NULL DEFAULT 120 CHECK (duration_minutes BETWEEN 30 AND 480),
    felt_product_id    TEXT,
    card_back_product_id TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    archived_at        TIMESTAMPTZ
);
CREATE INDEX standing_tables_owner_active ON standing_tables (owner_user_id) WHERE archived_at IS NULL;

CREATE TABLE standing_table_regulars (
    code        TEXT NOT NULL REFERENCES standing_tables(code) ON DELETE CASCADE,
    user_id     UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    first_joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_joined_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (code, user_id)
);
CREATE INDEX standing_table_regulars_user ON standing_table_regulars (user_id);
```

Add both tables to `db/Tables.kt` as Exposed objects, and add `selectAll` lines for both to `src/test/kotlin/com/cards/server/db/DatabaseSchemaTest.kt`.

### 2. Domain and repository

- `domain/StandingTable.kt`: `data class StandingTable(code, ownerUserId, name, buyIn, maxSeats, weekday: DayOfWeek, localTime: LocalTime, timeZone: TimeZone, durationMinutes, feltProductId, cardBackProductId, createdAt, updatedAt, archivedAt)` using kotlinx-datetime types (already a server dependency).
- `domain/StandingTableSchedule.kt`: a pure `fun nextStart(table, now: Instant): Instant` implementing the `nextStartsAt` rule above. Walk forward from `now - duration` in the table's zone. Use `LocalDateTime.toInstant(timeZone)` so DST is handled by the library.
- `domain/StandingTableRepository.kt` (interface) and `data/PostgresStandingTableRepository.kt`:
  - `create(table)`, `find(code)`, `listForUser(userId)` (owned plus regular-of, non-archived), `countActiveOwnedBy(userId)`, `update(code, patch)`, `archive(code, now)`, `upsertRegular(code, userId, now)`, `removeRegular(code, userId)`, `regularCount(code)`, `exists(code)` (true even when archived, because archived codes stay reserved).
- Wire it with `@SingleIn(ServerScope::class) @ContributesBinding(ServerScope::class) @Inject`, and expose it on `di/ServerComponent.kt`.

### 3. Validation (`domain/StandingTableRules.kt`, pure, unit tested)

- Name: trim, collapse runs of whitespace to one space, reject control characters, length 1 to 32. If the server checks display names against a blocklist anywhere, apply the same check. The name appears on a public preview page.
- `maxSeats` 2 to 9, `buyIn` 100 to 1,000,000,000. Reuse the constants `RoomRoutes.kt` already enforces (around lines 352–353); don't duplicate the numbers.
- `localTime` parses as `HH:mm`, minutes in {00, 15, 30, 45}.
- `timeZone` must parse with `TimeZone.of`. Reject `UTC` offsets like `+05:00`, so DST stays correct.

### 4. Reserved codes (fixes a real overwrite bug as well)

`InMemoryRoomService.generateCode` (around lines 860–870) only checks the in-memory map, and `PostgresRoomStore.save` does UPDATE-then-INSERT, so a fresh code that matches a persisted room not loaded in memory silently overwrites it.

- Add `suspend fun codeTaken(code): Boolean` to the room store (`exists in rooms`) and use `standingTables.exists(code)`.
- Move code generation into a `RoomCodeAllocator` (domain interface plus an implementation that takes `Random`, the room store and the standing table repository). It retries with the same alphabet and length (`InMemoryRoomService.kt:907–912`) until a code is free in memory, in `rooms`, and in `standing_tables`.
- Both ordinary room creation and standing table creation use it.
- Test first: a test in `PostgresRoomStoreTest` or a new allocator test where `Random` is seeded to collide with a saved room, then with a standing table. It must fail before the change and pass after.

### 5. Creating a room from a standing table

- Add `RoomService.createWithCode(code, hostUserId, settings, visibility = Private, felt, cardBack): RoomState` in `domain/Room.kt` and `InMemoryRoomService`, under the same global mutex as `create`. If a room with that code is already in memory or can be loaded from Postgres, return it unchanged; this is the race where two friends open the table at once. It skips `MAX_ROOMS_PER_HOST`.
- In `routes/RoomRoutes.kt`, the join handler (around lines 154–212): when `rooms.find(code)` is null, look up the standing table.
  - Missing: 404 `room_not_found`, as today.
  - Archived: 404 `standing_table_closed`.
  - Active: run the existing balance check against `table.buyIn`, then `createWithCode`, then continue the normal join path (the caller is already seated, so `alreadyJoined = true`).
- After any successful join where `standingTables.exists(code)` is true, call `upsertRegular(code, userId, now)`. That's one indexed upsert per join, which is fine.
- Teardown stays as it is. When the last human leaves, the room, members and `room_sessions` go away, and the standing table row stays.
- `deleteStaleRooms` (keyed on `rooms.created_at`) only touches `rooms`, so standing tables are safe from it. Add a test that proves it.

### 6. Routes `routes/StandingTableRoutes.kt` and `routes/StandingTableDto.kt`

- Implement the endpoint table above, registered inside the authenticated block in `Application.kt`'s `routing {}` next to `roomRoutes`.
- DTO mapping: `ownerDisplayName` comes from `profiles`. `live` comes from the in-memory room if one is loaded (seated humans are members with `bot_personality == null` that are connected). `isOwner` compares against `call.userId()`.
- Add `standingTable` to the server `RoomDto` (`routes/RoomDto.kt`), filled wherever a `RoomDto` is built (HTTP and socket snapshots). Cache standing-table lookups per room in memory, so the socket path doesn't query Postgres on every frame: load once when the room is created or loaded, and refresh on PATCH.

### 7. Tests (server)

- `StandingTableScheduleTest`: next start before, during and after tonight's window; Sunday to Monday wrap; the US spring-forward and fall-back weeks for `America/New_York`; a far-east zone (`Asia/Tokyo`) whose local weekday differs from the UTC weekday.
- `StandingTableRulesTest`: every validation rule.
- `PostgresStandingTableRepositoryTest` (extends `DatabaseTest`): create, list owned plus regular-of, archive hides it from lists but `exists` stays true, cascade on user delete.
- `StandingTableRoutesTest` (pattern of `RoomRoutesTest`): create, the cap of 3, PATCH by a non-owner is 403, DELETE archives.
- `FullStackRoomTest`: add a case where user A creates a standing table and user B joins by code with no live room. B gets 200, the room exists with the table's buy-in and seat count, and B is a regular. Everyone leaves and the room is torn down. C joins the same code later and gets a fresh room. Then archive, and a join gets 404 `standing_table_closed`.

**Done means:** server tests are green, the migration applies on a fresh Testcontainers DB, and the old-client path (plain join by code) works with no client changes.

---

## T2: Share page, calendar file and preview image (server)

**Why the server:** `downcard.app` is a static Astro site on GitHub Pages. Pages serves `/join/CODE` as `404.html` with a 404 status, and link-preview crawlers don't run the redirect script, so today's invite previews say "Not found · Downcard". Previews that differ per table need a server.

### Domain

- The pages are served by the prod Ktor app on Fly at `https://join.downcard.app`. The owner adds the DNS record and certificate (see "Owner steps"). Until then they are reachable at `https://cards-server-prod.fly.dev/j/{CODE}`, which is how to test them.
- Route paths: `GET /j/{code}` (page), `GET /j/{code}/calendar.ics`, `GET /j/{code}/preview.png`. On the `join.downcard.app` host, also serve the same page at `GET /{code}`, so links read `join.downcard.app/K7M2QX`. Use Ktor's `host("join.downcard.app") { ... }` for the bare-code route so it can't shadow anything on the API host. Validate `code` against the room code alphabet and return a plain 404 page if it doesn't match.
- No authentication. Add these routes to the existing rate limiting (`plugins/RateLimits.kt`) with a per-IP limit (60/minute is plenty).

### The page (`routes/SharePageRoutes.kt`, HTML from a small template in `src/main/resources/share/`)

- Look up the standing table, then the live room. Three cases:
  1. **Standing table:** title `"{name} · Downcard"`. Description `"Every {Thursday} at {8:00 PM} {EST}. Room code {CODE}. Tap to grab a seat."` Show the host's display name: "{Maexj}'s table".
  2. **Live ordinary room:** title `"Join my poker game on Downcard"`, description `"Room code {CODE}. {3} seated. Tap to join."`
  3. **Unknown code:** a friendly "This table isn't open right now" page that still offers the stores. Return HTTP 200 (a 404 makes some apps drop the preview).
- `<head>`: `og:title`, `og:description`, `og:image` (absolute URL to `preview.png`, plus `og:image:width` 1200 and `og:image:height` 630), `og:url`, `og:type=website`, `twitter:card=summary_large_image`, `robots noindex`.
- Body: copy the look and behaviour of `website/src/pages/join.astro` (code shown big with tap-to-copy, "Open in Downcard" to `cards://join/{CODE}` on phones only, only the store for this phone, tagged with the invite campaign from `website/src/lib/room-invite.ts`). Add for standing tables:
  - **Add to calendar**: on iPhone a link to `calendar.ics` (Safari opens the system "Add to Calendar" sheet). On Android and desktop, a Google Calendar template link `https://calendar.google.com/calendar/render?action=TEMPLATE&text=…&dates=…&recur=RRULE:FREQ=WEEKLY;BYDAY=TH&ctz={tz}&details=…`, plus a smaller "Download .ics" link.
- HTML-escape every user value (table name, display name). Write a test that a name like `<script>` comes out escaped in both the body and the meta tags.

### Calendar file

- `text/calendar; charset=utf-8`, filename `"{name}.ics"`. One `VEVENT`: `UID:{code}@downcard.app`, `DTSTART;TZID={tz}:{next local start}`, `DURATION:PT{minutes}M`, `RRULE:FREQ=WEEKLY;BYDAY={MO..SU}`, `SUMMARY:{name} · Downcard`, `URL:{share url}`, `DESCRIPTION:Tap to join: {share url}\nRoom code {CODE}`, and a `VALARM` 15 minutes before. Include a `VTIMEZONE` block, or rely on `TZID` with an IANA name; both Apple and Google accept IANA `TZID`. Fold lines at 75 octets.
- Put the builder in a pure `IcsBuilder` with a golden-file unit test.

### Preview image

- 1200×630 PNG drawn with `java.awt` (headless) in `share/PreviewImageRenderer.kt`. Set `-Djava.awt.headless=true` in the server JVM options if it isn't already.
- No generated artwork. The background is the app's felt colour as a vertical gradient, using the hex values from the design-system tokens (`libraries/ui/.../system/color`; copy the two felt values into a server constant with a comment naming the token). Draw a real card back from the app's resources if one is a PNG; otherwise skip it.
- Text uses the app's fonts, copied into `apps/server/src/main/resources/share/fonts/` from `libraries/ui/src/commonMain/composeResources/font/` (`DMSerifText-Regular.ttf` for the table name, `Roboto-Medium.ttf` for the rest). Lines:
  1. table name, large, ellipsized to fit;
  2. "Thursdays · 8:00 PM EST";
  3. "Code K7M2QX" in a pill;
  4. the "Downcard" wordmark small at the bottom.
- For an ordinary live room: "Join my poker game", "Code K7M2QX".
- Cache rendered PNGs in memory by `(code, updatedAt)`, with a small LRU of 200. Send `Cache-Control: public, max-age=300`.
- Test: render one standing table and one ordinary room. Assert the size is 1200×630 and the output is a valid PNG, and save both under `build/` so a reviewer can look at them.

### Fix existing invites too

Once `invite.shareBaseUrl` is switched (T4), every invite, standing or not, uses this page. That fixes the broken "Not found" preview for ordinary rooms as well.

**Done means:** `curl -s https://cards-server-dev.fly.dev/j/{CODE}` returns the page with correct meta tags. The `.ics` imports into Apple Calendar and Google Calendar with a weekly repeat in the right zone (check by importing the dev `.ics` once on the Mac). The PNG looks right when the link is pasted into Messages on the Mac.

---

## T3: Website stopgap (static site)

Small and immediate. It improves previews for links already in the wild, before T2's domain is live.

1. `website/src/pages/404.astro`: nearly all 404 traffic is `/join/CODE`. Override the Layout's title and description and add an `og:image` so a crawler hitting `/join/CODE` sees "Join my game on Downcard", "Tap to grab a seat at my poker table." and `https://downcard.app/uploads/app-icon.png`. The redirect script stays.
2. `website/src/lib/room-invite.ts`: tighten the code regex to the server alphabet (`^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$`) and update `room-invite.test.ts`.
3. Add `npm test` to `.github/workflows/pages.yml` before the build. Website tests don't run in CI today.

**Done means:** `npm test` passes; after deploy, pasting a `downcard.app/join/XXXXXX` link into Messages shows the invite title, not "Not found".

---

## T4: Client data layer, flags, share URL

### Flags (`libraries/rooms/src/commonMain/kotlin/com/cards/libraries/rooms/RoomsConfigValues.kt`, new file)

Follow `libraries/social/.../SocialConfigValues.kt:21–75` exactly:

- `class StandingTablesEnabled : FlagConfigValue`, path `standingTables.enabled`, default `false`, with `forTest(enabled)`, multibound as `QaConfigValue` so it shows in the QA menu.
- `class InviteShareBaseUrl : StringConfigValue`, path `invite.shareBaseUrl`, default `"https://downcard.app/join"`, with `forTest(url)`.
- Add both to `apps/admin/config-manifest-registry.json` if `ConfigManifestDriftTest` requires it (run the test to find out).

### Share URL

- `features/lobby/src/commonMain/kotlin/com/cards/features/lobby/RoomInvite.kt`: change `shareUrlForCode(code)` to `shareUrlForCode(code, baseUrl: String)` and drop the constant `SHARE_BASE_URL`.
- The two call sites (`LobbyScreen.kt` around line 295, `PlayMultiplayerFeatureEntryPoint.kt` around lines 204 and 270) get the base URL through their ViewModels or entry points from `InviteShareBaseUrl`. Trim any trailing slash.
- Update `RoomInviteTest`.

### Models (`libraries/rooms/src/commonMain/.../`)

- `StandingTable` (mirrors `StandingTableDto`; use `DayOfWeek`, `LocalTime`, `TimeZone`, `Instant`).
- `StandingTableSummary` (mirrors the room's summary).
- Add `standingTable: StandingTableSummary?` to the `Room` model.
- `StandingTableDraft(name, buyIn, maxSeats, weekday, localTime, timeZone, durationMinutes, feltProductId, cardBackProductId)`.

### Repository (`StandingTableRepository` in `libraries/rooms`, implementation in `libraries/rooms/impl`)

```kotlin
interface StandingTableRepository {
    val mine: Flow<List<StandingTable>>
    suspend fun refreshMine(): Catching<Unit>
    suspend fun create(draft: StandingTableDraft): CreateStandingTableOutcome
    suspend fun get(code: String): Catching<StandingTable>
    suspend fun update(code: String, draft: StandingTableDraft): Catching<StandingTable>
    suspend fun archive(code: String): Catching<Unit>
    suspend fun leave(code: String): Catching<Unit>
}
sealed interface CreateStandingTableOutcome {
    data class Created(val table: StandingTable) : CreateStandingTableOutcome
    data object TooMany : CreateStandingTableOutcome
    data class Invalid(val field: String) : CreateStandingTableOutcome
    data object NetworkError : CreateStandingTableOutcome
}
```

- HTTP goes through `networkClient.authedCall("standing_tables.<op>")`, as in `RoomApi.kt`. DTOs go in `libraries/rooms/impl/.../StandingTableDto.kt`. Add `standingTable` to the client `RoomDto` and map it.
- `mine` is held in memory and refreshed on Home resume and after any write. Choose the row in AGENTS.md "Repository read-path caching" that fits (most likely "in-memory, refresh on screen resume") and name it in the commit body.
- Add `JoinRoomOutcome.StandingTableClosed`, mapped from 404 + `standing_table_closed` in `RoomRepositoryImpl.joinRoom` (around line 146). Log `room.join_failed` with reason `standing_table_closed`.
- Fakes: add a `FakeStandingTableRepository` next to `FakeRoomRepository` in the lobby test fakes (`features/lobby/impl/src/commonTest/.../LobbyFakes.kt`) and wherever Home's fakes live (`HomeViewModelTest.kt`).
- Tests: repository mapping tests with a fake HTTP engine, following the existing `RoomRepositoryImpl` tests.

**Done means:** the client compiles on Android and iOS, the tests are green, and nothing is visible yet.

---

## T5: `CalendarLauncher`

There's no calendar code in the app today. Follow the `ShareLauncher` shape: a common interface with a platform implementation bound per platform, no `expect`/`actual`.

### Interface (`libraries/navigation/src/commonMain/kotlin/com/cards/libraries/navigation/CalendarLauncher.kt`)

```kotlin
data class RecurringCalendarEvent(
    val title: String,
    val notes: String,
    val url: String,
    val firstStart: Instant,
    val durationMinutes: Int,
    val timeZone: TimeZone,
    val weekday: DayOfWeek,
    val alertMinutesBefore: Int = 15,
)

interface CalendarLauncher {
    suspend fun addWeekly(event: RecurringCalendarEvent): CalendarResult
}

enum class CalendarResult { Added, Cancelled, Unavailable }
```

Expose it through `Router` (`Router.addToCalendar(event)`), next to `shareText`, so composables and ViewModels never touch it directly. `DelegatingRouter` logs failures like it does for share.

### Android (`libraries/navigation/impl/src/androidMain/.../AndroidCalendarLauncher.kt`)

- `Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)` with:
  - `Events.TITLE`, `Events.DESCRIPTION` (the notes plus the URL), `Events.EVENT_LOCATION` = URL;
  - `CalendarContract.EXTRA_EVENT_BEGIN_TIME` / `EXTRA_EVENT_END_TIME` in epoch milliseconds;
  - `Events.EVENT_TIMEZONE` = zone id;
  - `Events.RRULE` = `FREQ=WEEKLY;BYDAY=TH`.
- No permission is needed. Start it from the current activity (`ActivityProvider.currentActivity()`, as `AndroidReviewLauncher` does).
- Return `Added` once the intent starts. Android doesn't report the result, so log it as `opened`.
- If `resolveActivity` is null, return `Unavailable`. The caller then falls back to opening the `.ics` URL with `WebLinkLauncher`.
- The insert intent can't set reminders; that's accepted.

### iOS: Swift Twin (`apps/ios/iosApp/Platform/CalendarLauncher.swift`)

Follow `ReviewLauncher.swift` and how it's passed into `IosAppComponent.create(...)` (`apps/compose/src/iosMain/kotlin/com/cards/IosAppComponent.kt:32–50`, constructed in `iOSApp.swift:8–24`).

- Create an `EKEventStore`, build an `EKEvent` (title, notes, `url`, `startDate`, `endDate`, `timeZone`), add an `EKRecurrenceRule(recurrenceWith: .weekly, interval: 1, daysOfTheWeek: [EKRecurrenceDayOfWeek(...)], ...)` and an `EKAlarm(relativeOffset: -900)`.
- Present an `EKEventEditViewController` with `eventStore` and `event` from the top view controller.
- On iOS 17 and later this controller runs outside the app and needs no calendar permission. The app's floor is 18.2, so don't request access and don't add an `NSCalendars*UsageDescription`. Confirm on the simulator that no permission prompt appears; if one does, add `NSCalendarsWriteOnlyAccessUsageDescription` with the string "Downcard adds your standing table to your calendar." and note it in the ledger.
- Map the delegate's `.saved` to `Added` and `.canceled` to `Cancelled`. Bridge with a `suspend` function as `ReviewLauncher` does (`@ObjCName` on the Kotlin interface).

### Tests

- A pure `fun weeklyRule(weekday): String` in common code, unit tested for all seven days (used on Android and for the web Google link format check).
- Manual checks: on the `Pixel_9` emulator and the `iPhone 17 Pro` simulator, the add-event screen opens with the title, the right first date and time in the table's zone, and "Repeat: Weekly". Screenshot both for the ledger. Shut down both devices afterwards.

---

## T6: Client UI

All new UI is behind `StandingTablesEnabled()`. With the flag off, the app must look exactly as it does today.

### Routes (`features/lobby/src/commonMain/.../StandingTableRoutes.kt`)

- `class StandingTableCreateRoute : Route(authRequirement = AuthRequirement.Account)`
- `data class StandingTableRoute(val code: String) : Route(authRequirement = AuthRequirement.Account)` (detail screen)
- `data class StandingTableEditRoute(val code: String) : Route(...)`
- Register them in `LobbyFeatureEntryPoint` with `screen<…>`.

### Shared form pieces

`PrivateCreateScreen.kt` keeps its form state in the composable and has no ViewModel. Pull the buy-in slider, seat stepper and felt/card-back pickers out into internal composables in `features/lobby/impl/.../create/` so both screens use them, without changing how `PrivateCreateScreen` behaves. Do that refactor in its own commit first.

### New design-system pieces (`libraries/ui`)

- `WeekdayPicker(selected: DayOfWeek, onSelect)`: seven single-letter chips (M T W T F S S, localized by `DayOfWeek` display name, first letter), built on the existing selectable chip or `SelectableCard` styles.
- `TimeOfDayPicker(time: LocalTime, onChange)`: hour stepper (1–12), minute segmented control (00, 15, 30, 45) and an AM/PM toggle. Use 24-hour display when the device locale uses it, if the codebase already has a locale check for time formatting; otherwise 12-hour.
- Each one gets a `@Preview`.

### Create screen (`StandingTableCreateScreen` + `StandingTableCreateViewModel`)

- **State:** name, weekday, time, buyIn, maxSeats, felt, cardBack, isSubmitting, error.
- **Defaults:**
  - weekday = today's;
  - time = 8:00 PM;
  - name = `"{Weekday} Night Poker"` as a placeholder the user can keep or replace (a hint, not prefilled text);
  - buy-in and seats as `PrivateCreateScreen`;
  - zone = `TimeZone.currentSystemDefault()`, shown as a small line "Times are in {zone city}".
- **Layout, top to bottom:** name field, "Every" + `WeekdayPicker`, "At" + `TimeOfDayPicker`, buy-in, seats, cosmetics, then a sticky primary button "Set up table".
- **On submit:** `repo.create(draft)`.
  - `Created` → navigate to `StandingTableRoute(code)` with `justCreated` behaviour (see detail).
  - `TooMany` → inline error "You already have 3 standing tables. Delete one to start another."
  - `Invalid` → field error.
  - `NetworkError` → the usual retry message.

### Detail screen (`StandingTableScreen` + `StandingTableViewModel(code)`)

- **Header:** table name (DS serif display style), "Thursdays · 8:00 PM", "Next: Tonight" / "Tomorrow" / "Thu, Nov 20". If `live` is set: a "Live · 3 playing" tag.
- **Code block:** the code large, tap to copy (reuse the lobby's code component from `libraries/ui/components/room`).
- **Buttons:**
  - primary "Open table" → `LobbyRoute(prefilledCode = code)` (the existing join path; the server creates the room);
  - secondary "Invite friends" → share;
  - secondary "Add to calendar" → `router.addToCalendar(...)`;
  - owner-only ghost "Edit" and danger "Delete table" (confirm dialog);
  - non-owner ghost "Remove from my tables".
- **Right after creation:** show a short line under the header, "Share the invite and add it to your calendar. The code works every week." Then emphasize Invite friends as the primary button and make Open table secondary.
- **Share text** (new string `standing_table_share_message`): `"{name}: poker every {Thursday} at {8:00 PM}. Tap to join, or use code {CODE}: {url}"`.
- **Calendar event title:** `"{name} · Downcard"`. Notes: `"Tap to join: {url}\nRoom code {CODE}"`.

### Home

- **"Your tables" section** in `HomeScreen.kt`, placed above the PlayRows (the PlayRow block starts around line 246), shown only when the flag is on and `mine` is non-empty.
  - One compact card per table: name, "Thursdays · 8:00 PM", and a right-side state: "Live · 3" (accent), "Tonight" or "On now", or the next date.
  - Tap → `StandingTableRoute(code)`.
- **The private chooser** (`features/home/impl/.../PrivateChooseSheet.kt:55`) gains a third row when the flag is on: "Standing table", subtitle "Same friends, same night, every week." → `StandingTableCreateRoute`.
- `HomeViewModel`: inject `StandingTableRepository` and `StandingTablesEnabled`. Refresh `mine` on `ScreenResumed`. Expose `standingTables: List<StandingTableCardModel>` in `HomeState`.

### Lobby and table

- When `room.standingTable != null`:
  - the lobby header shows the table name instead of `private_create_default_room_name`;
  - a slim banner under the code reads "Standing table · Thursdays 8:00 PM" with an "Add to calendar" text button.
- Share from the lobby and the in-game share use the standing share text.
- `JoinRoomOutcome.StandingTableClosed` shows "This standing table was closed by its host." in both `PrivateJoinScreen` and the lobby's join error states.

### Telemetry (add every event to `docs/wiki/app-events.md`, new section `## Standing tables`)

| Event | Attributes |
|---|---|
| `standing_table.create_started` | `entry` (chooser, feature_intro) |
| `standing_table.created` | `weekday`, `hour`, `max_seats`, `buy_in` |
| `standing_table.create_failed` | `reason` (too_many, invalid, network) |
| `standing_table.viewed` | `is_owner`, `live` |
| `standing_table.opened` | `entry` (detail, home_card) |
| `standing_table.invite_shared` | `surface` (detail, lobby, in_game) |
| `standing_table.calendar_added` | `surface`, `result` (added, cancelled, unavailable, opened) |
| `standing_table.archived` | none |

No user ids or names in attributes.

### Tests

- ViewModel tests for the create and detail screens (`CoroutineTest`, Turbine), covering every outcome.
- A `HomeViewModelTest` case for the section appearing and staying hidden with the flag off.
- A lobby test showing the standing name and banner.
- Previews:
  - create: empty, filled, submitting, error;
  - detail: owner, non-owner, live, just created;
  - Home with one, two and three tables.

---

## T7: One-time feature intro sheet

Build this as a reusable pattern. Standing tables is the first entry; future features add one enum value and one content block.

1. **AppCache** (`libraries/cards/src/commonMain/kotlin/com/cards/libraries/AppCache.kt:54`): add `featureIntrosSeen: Set<String> = emptySet()`. Make it **per device**: leave it out of `resetAccountScoped()` (lines 410–460), so switching accounts doesn't replay it. Write the reason in the commit body.
2. **Model:** `enum class FeatureIntro(val id: String) { StandingTables("standing_tables") }` in `features/home/impl/.../notification/`.
3. **Snapshot and arbiter** (`notification/GetHomeScreenNotification.kt`):
   - add `featureIntrosSeen`, `standingTablesEnabled` and `handsPlayed` to `HomeNotificationSnapshot`;
   - add `HomeNotification.Blocking.FeatureIntro(intro)`;
   - priority goes after every existing Blocking case except `UpdateAvailable`, so it never stacks on Welcome, LevelUp, achievements or OutOfChips;
   - eligible when the flag is on, `handsPlayed >= 1` (don't add to a brand-new player's first session) and `"standing_tables" !in featureIntrosSeen`.
4. **ViewModel and entry point:** in `HomeViewModel.presentPendingBlocking()` (around line 543), follow the `UpdateAvailable` handling: mark seen first, wait `DialogIntroDelay`, then send `HomeEvent.ShowFeatureIntro(intro)`. `HomeFeatureEntryPoint` shows `FeatureIntroSheet` as local state, like `UpdateAvailableSheet` (lines 284–293).
5. **Sheet** (`features/home/impl/.../FeatureIntroSheet.kt`):
   - `BottomSheet` with `topAccessoryEmoji("🗓️").asDragHandle()`.
   - Small "New" tag, title "Standing tables".
   - Body: "Name your table, pick a night, and invite your friends. The code works every week, and the game goes straight into everyone's calendar."
   - Below the body, a live mock built from real DS components (not an image): the same compact card Home uses, showing "Thursday Night Poker", "Thursdays · 8:00 PM" and "Tonight".
   - Primary "Set one up" → `StandingTableCreateRoute` (log `feature_intro.acted`, action=primary). Ghost "Maybe later" dismisses (action=dismiss).
   - Log `feature_intro.shown` with `feature=standing_tables`.
   - `@Preview` for the sheet.
6. **Tests:** `GetHomeScreenNotificationTest` cases:
   - shows when eligible;
   - hidden when the flag is off;
   - hidden with 0 hands;
   - hidden once seen;
   - loses to Welcome and LevelUp;
   - wins over UpdateAvailable.

Plus a `HomeViewModelTest` case that seen is written before the event is sent.

---

## T8: Docs

- `docs/QA.md`: a `## Standing tables` section with cases `ST-1` to `ST-6` in the existing format:
  - create a table;
  - share and open the link on a phone without the app (web page, Add to calendar, store);
  - friend joins by link and becomes a regular;
  - a 0.8.0 build joins by typed code;
  - add to calendar on both platforms;
  - archive, then join fails with the closed message.
  - Plus `FI-1` for the feature intro (shows once, never on the first session).
- `docs/decisions.md`: a 2026-11-14 entry, "Standing tables: a durable code with calendar reminders". Cover what this changes about the 2026-10-21 rejection, server-rendered previews on `join.downcard.app`, materializing the room inside the existing join endpoint for old clients, and regulars without RSVP.
- `docs/wiki/app-events.md`: confirm every event from T0, T6 and T7 is listed.
- `docs/release-notes/<next version>.md`: draft only, kept dark-friendly: "Fixed link previews for invites." Write the standing-tables note when the flag turns on, not before.
- `docs/todo.md`: remove ROOM-24, ENG-89 and SITE-2 when they are done.

---

## Owner steps (only these need a human)

1. **DNS for `join.downcard.app`:** at the domain registrar, add `CNAME join → cards-server-prod.fly.dev`. Then run:

   ```bash
   fly certs add join.downcard.app -a cards-server-prod
   ```

   Wait for `fly certs show join.downcard.app -a cards-server-prod` to report the certificate as issued.
2. **Approve the prod server deploy** in GitHub Actions (the `production` environment gate) after T1 and T2 merge.
3. **Switch the share URL:** once `https://join.downcard.app/{any live code}` loads, set `invite.shareBaseUrl` to `https://join.downcard.app` in the admin console. Every invite from every app version that reads the flag starts using the new previews.
4. **Turn it on:** after the app release with T4 to T7 is live on both stores, set `standingTables.enabled` to true. Optionally start with your own user id in an allow-list rule and try it first.

## Rollout order

1. Server (T1, T2) merges and deploys. Old clients are unaffected; join-by-code to a standing code works for everyone.
2. The website stopgap (T3) deploys on push.
3. The app release carries T0 and T4 to T7 dark.
4. The owner does the DNS and share-URL steps (they don't depend on the app release).
5. The owner turns on `standingTables.enabled`.

## How we'll know it worked (check two and four weeks after the flag goes on)

- Standing tables created, and the share of creators who share an invite (`standing_table.invite_shared` / `standing_table.created`). Target: over 70%.
- Tables that get played in a second week: a room is created from the code in two different weeks. Target: over 30% of tables.
- Weekly players who played at a standing table.
- Day-7 return for players who joined a standing table, compared with everyone else (`profiles` joined to `standing_table_regulars` and `player_stat_events`).
- New accounts whose first join was a standing table code (the invite campaign tag on the store links, plus `room.joined` on a standing code in the first session).

If fewer than 10% of weekly players touch a standing table after four weeks, look at why before building on it.

## Later (not in this build)

- Universal links on `join.downcard.app` (ROOM-23 moves to this host: serve `/.well-known/apple-app-site-association` and `assetlinks.json` from the same Ktor routes).
- Chips for both host and friend when an invited friend finishes a first hand.
- Biweekly or multiple nights. Season stats per table ("Maexj is up 12,000 at Thursday Degens").
- A Home nudge on the table's night ("Thursday Degens starts in an hour"), shown only while the app is open, since there's no push.
