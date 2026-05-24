# Cards — Voice & Copy Guide

**Last reviewed:** 2026-05-16 · **Status:** Active · **Owner:** Design · **Companion to:** [product-spec.md](./product-spec.md)

How Cards talks to its users. This is a reference doc — look up the surface you're writing for, copy the pattern, then adapt. Specific copy is more important than abstract principles, so this document is mostly examples.

---

## 1. Voice principles

Cards' voice is **confident, dry, and quietly funny.** It respects the user's intelligence and time. Three rules carry most of the weight:

### 1.1 Never beg

We do not say "Come back!" "We miss you!" "Don't leave!" or any variant. Users can play or not play. We're not hurt either way. This rule is absolute and overrides every retention impulse.

### 1.2 Confident, not cheerful

We are not Mailchimp. We don't celebrate small actions with confetti. When something matters, the celebration is real (achievement unlock, level-up, league promotion). When it doesn't, the response is quiet. Most of our copy is short and direct.

### 1.3 Treat the user as an adult

No "champ," "high roller," "ace," "boss." No exclamation marks unless something is actually exciting. No "Oops!" before error messages. No infantilizing.

---

## 2. Tone modulation

| Surface | Tone | Why |
| --- | --- | --- |
| First-session welcome | Quiet, slightly warm | They just showed up; don't overwhelm |
| Achievement unlock | Earned, celebratory | Real moment — let it land |
| Level-up | Brief, confident | Worth marking, not worth dwelling |
| League promotion | Celebratory | A real win |
| League demotion | Generous, supportive | Never punitive — "you'll be back" |
| Bust (lose all chips) | Generous, soft | The "second wind" moment — kind, not pitying |
| Error / failure | Apologetic, brief, never blaming | "Connection lost" not "You disconnected" |
| Push notification | Event-stating, never urgent | We trust the user to decide whether to come |
| Shop / IAP | Neutral, no pressure | They asked to be here; just tell them the price |
| Settings / privacy | Plain, precise | This is where trust lives |
| Moderation (report / block) | Calm, matter-of-fact | We don't dramatize bad behavior |

---

## 3. The never-say list

Patterns that are off-brand regardless of context:

- **"You've earned a free spin!"** — slot-machine language
- **"Your chips are running low!"** — predatory urgency
- **"Limited time!"** — manufactured scarcity (limited-time *items* are fine; "limited time" *as urgency framing* is not)
- **"Welcome back, high roller"** — false familiarity, fake aspiration
- **"Oops!"** — infantilizing
- **"We miss you!"** — needy, off-brand
- **"Don't lose your streak!"** — we don't have streaks
- **"Daily reward waiting!"** — Zynga-shaped
- **"You're on fire!"** — empty hype
- **"Get more chips!"** as a CTA — pressure copy; the shop is where chips live, no CTA needed
- **"Tap to unlock the next level!"** — there's no unlocking, just playing
- **Multiple exclamation marks.** Ever.
- **All caps for emphasis.** Use weight or color.
- **"Ka-ching"-style copy on win moments.** Wins are real; let them be real.

---

## 4. Microcopy patterns

### 4.1 Button labels

- Primary CTA on home: **"Quick Match"** (not "Find a game!" or "Play now!")
- Bot game start: **"Play Bots"** (not "Practice mode" — too clinical; not "Train" — sounds homework-y)
- Friend invite: **"Invite"** (not "Send invite!" or "Share with friend")
- Confirm large action (buy chips, claim account, delete account): **"Confirm"** + a clear short summary above
- Cancel: **"Cancel"** (not "Nevermind" or "Maybe later")
- Settings save: **"Save"** (not "Save changes!" — extra word, extra punctuation)
- Skip onboarding: **"Skip"** (always available, never demanded)

### 4.2 Empty states

Pattern: *plain factual statement* + *one inviting action*.

- **Friends list, none yet:** "No friends yet. Invite someone to a game." → `[Invite]`
- **Recently played with, none yet:** "Play your first multiplayer hand and it'll show up here." → `[Quick Match]`
- **Leagues, unranked:** "Leagues rank multiplayer XP. Play a hand with friends or join a public room to enter the ladder." → `[Quick Match]`
- **Achievements, none unlocked:** "Achievements show up here as you play."
- **Shop, empty featured:** doesn't happen; we always have something featured

### 4.3 Error messages

Format: *what happened* + *what to do*. Never blame the user. Never use "Oops."

- **Lost connection mid-hand:** "Connection lost. We're keeping your seat warm — back in a moment."
- **Failed to join room:** "Couldn't join — the room may be full or already started. Try Quick Match for an open seat."
- **IAP failed:** "Purchase didn't go through. You weren't charged. Try again in a moment."
- **Server error / 500:** "Something's not right on our end. We're looking at it." (don't blame infra publicly)
- **Banned / suspended (403):** "Your account is temporarily restricted. You can appeal at support@..." (never punitive copy, even for actual bad actors — let the action speak)

### 4.4 Confirmation dialogs

For destructive or significant actions only. Two-line max.

- **Delete account:** "This removes your profile, achievements, and chip balance permanently. We can't reverse this." → `[Cancel] [Delete]`
- **Claim account (anonymous → claimed):** "Linking will save your progress to this Apple ID. Your chips, achievements, and level move with you." → `[Cancel] [Link]`
- **Leave game with chips on the table:** "If you leave, your stack is returned to your wallet and the seat opens up." → `[Stay] [Leave]`
- **Block user:** *no dialog* — block is one-tap, reversible from settings, doesn't need confirmation

### 4.5 Tooltip / first-time hints

- **Pulse-animated "Play Bots" on first home:** "Start here — meet the bots."
- **First time joining Quick Match:** "We'll find you an open seat at a stake tier matching your balance. Usually under 10 seconds."
- **First time seeing league:** "Leagues are weekly. Play multiplayer hands to climb. Top 7 promote on Monday."
- **First time at a table:** "Tap an opponent's avatar to see their profile."

---

## 5. Per-moment copy library

### 5.1 First-launch welcome (the one explainer screen)

> **Cards is where your group plays poker.**
>
> Climb the leagues. Collect the felt. No pay-to-win.
>
> `[Tap to start]`

Why this works: states what the app *is* in 4 words (the brand promise), states what's on offer (gamified meta), states what's not on offer (P2W), one button.

### 5.2 Achievement unlock — by rarity

- **Common:** *"Achievement: First Hand"* — top-banner toast, 2 seconds, no animation modal
- **Rare:** *"Achievement: 100 Hands"* — Apple-Fitness-style flip + brief shimmer
- **Epic:** *"Achievement: Final Table"* — full flip animation, reward callout, sticks until tapped
- **Legendary:** *"Achievement: Royal Flush"* — full celebration, share prompt, *"Want to pin this to your profile?"*

No exclamation marks anywhere. The animation conveys the energy.

### 5.3 Level-up

> *"You're Level 12."*

That's it. No "Congrats!" no "You leveled up!" No fanfare beyond the visual badge update. If it's a milestone level (10, 25, 50), unlock a title:

> *"Level 25 reached. Title unlocked: Felt Veteran."*
> `[Equip now] [Later]`

### 5.4 League moments

- **Cohort assignment:** *"You're in the Bronze league this week. 29 others, ranked by hands played. Top 7 promote on Monday."*
- **Promotion (Monday):** *"Promoted to Silver. New cohort, same rules."* — celebratory animation, badge update
- **Demotion (Monday):** *"You're back in Bronze for next week. The ladder waits."* — never "you lost," never "demoted." Soft phrasing.
- **Hold (Monday):** *"Holding Silver. New 30-player cohort starts now."* — neutral, factual

### 5.5 Bust protection (the "second wind" moment)

User hits 0 chips:

> *"Welcome back to the table."*
>
> *"+1,000 chips on the house. No timer, no catch — Cards is play-money."*
>
> `[Play]`

Why this works: warm, not pitying. States the gift. States the lack of urgency (the key brand differentiator from FanDuel patterns). One button, no upsell.

### 5.6 Friend invite (the share sheet)

Generated link preview:

> **QuietAce72 wants to play poker.**
> *Stakes: 500/1,000 · 4 seats open · Cards*

Share targets: system share sheet (iMessage, WhatsApp, copy link). No app-specific share targets, no platform pressure.

In-app prompt:

> *"Invite someone?"*
> `[Share]` `[Maybe later]`

Never "Spread the love!" or "Tell your friends about Cards!"

### 5.7 IAP / shop copy

Buying chips:

> **5,000 chips**
> $1.99
> *Three packs available. Chips fund table buy-ins and shop purchases.*
> `[Buy]`

Buying a cosmetic:

> **Midnight Card Back**
> 1,500 chips
> *Purely cosmetic. Visible to other players at the table.*
> `[Buy]` `[Cancel]`

Earned item shown in My Items (unlock-only):

> **Royal Flush Champion — Season 1**
> *Awarded to the player who won the Royal Flush Tournament during Season 1. Not for sale.*

The "Not for sale" line is the brand signature. Use it.

### 5.8 Notification copy

The four allowed categories (per [spec §8](./product-spec.md#8-notifications)). All event-driven, never time-of-day.

- **League — promotion within reach:** *"You're 4 spots from promotion. League resets in 2 days."*
- **League — demotion risk:** *"You're in the bottom 5 of your league. 2 days left to climb."*
- **League — weekly reset:** *"Promoted to Silver. New cohort starts now."* (fired Monday on the actual promotion)
- **Friend — joined a table:** *"QuietAce72 just sat down at a 500/1,000 table. 4 seats open."*
- **Friend — achievement:** *"QuietAce72 just hit Royal Flush."*
- **Battle pass — tier unlocked:** *"Season tier 12 unlocked."*
- **Battle pass — season ending:** *"Season 1 ends in 3 days."*
- **Achievement — Legendary unlock:** *"Achievement: Royal Flush. The rarest in the game."*

Never send: streak notifications (no streak mechanic). Daily-quest / daily-challenge reminders (no quest mechanic — see [spec C.7](./product-spec.md#c7-todays-quests-rejected-2026-05-20)). Time-of-day "you usually play around now" pings. "Come back" / "we miss you" copy.

### 5.9 Onboarding skip / sign-in path

Launch screen:

> **Cards**
> *(logo)*
> `[Continue as guest]` (primary, pulse)
> `[Sign in]`

Sign-in screen:

> *"Welcome back. Sign in with the account you used before."*
> `[Apple]` `[Google]`
> `[Continue as guest instead]`

After sign-in:

> *"Welcome back, [their display name]."* — lands directly on home, no onboarding

**Anonymous account recovery on reinstall** (fingerprint or platform-keychain match — see [spec §6.1](./product-spec.md#best-effort-account-revival-on-reinstall)):

> *"Welcome back. We found your account."*
>
> `[display name] · Level [N] · [X] chips`
>
> `[Continue]` `[Start fresh]`

If `[Start fresh]` is tapped, secondary confirmation (since this is destructive):

> *"This will leave your old account behind. Chips, achievements, and level reset. Sure?"*
>
> `[Yes, start fresh]` `[Cancel]`

### 5.10 Moderation surfaces

**Block confirmation toast:**
> *"Blocked. You won't see them at your tables."*

**Report submission:**
> *"Reported. We'll review."*
> *(No notification to the reported user. No further status to the reporter — keeps the system unexploitable.)*

**Banned / suspended user trying to act:**
> *"Your account is temporarily restricted. You can appeal at support@..."*

**Host boots a player from a public room:**
> *"You've been removed from this table. You can join other rooms."*

Never escalate the language. Never moralize. The action is the message.

### 5.11 Claim copy (anonymous users)

Claim is opt-in — we never proactively prompt. (See [decisions.md 2026-05-20 — Drop proactive smart-claim prompts](../decisions.md) for the rationale.) Copy lives only on the surfaces where claim is naturally relevant.

**Profile screen — static claim card** (always visible to anonymous users; never modal)

> **Sign in to keep what you've built.**
>
> *Your chips, achievements, and friends ride along with your account. You'll need to sign in to host public rooms or add friends.*
>
> `[Sign in with Apple]` `[Sign in with Google]`

**Inline — anon user tries to host a public room**

> *"Hosting a public room means strangers will see your account. Sign in first so they're seeing a real one."*
>
> `[Sign in]` `[Back]`

**Inline — anon user tries to add a friend**

> *"Friends need a stable account on both sides. Sign in to add this person."*
>
> `[Sign in]` `[Back]`

**Never-do patterns:**
- Proactive modals at achievement / level / balance / shop moments (those are review-prompt moments — see §5.13)
- "Are you sure you want to skip?" confirmation on dismissal
- Re-firing claim copy after dismissal in the same session
- Adding urgency ("Only X hours left to save your progress!")
- Implying the user is at risk in punitive terms ("You could LOSE your chips!")

The claim card is an offer, not a threat. If a user plays anonymously forever, that's a respected choice — fingerprint recovery handles the common case for them.

### 5.12 Settings & privacy

Plain, precise, slightly clinical here. Trust lives in clarity.

- **Sound:** "Sound effects" `[off]` `[on]` — default off. (Per spec §1.4.)
- **Notifications:** category-by-category toggles, no master "enable all" pre-checked
- **Profile visibility:** `[Public]` `[Friends only]` `[Private]` — explained inline: *"Friends only: your level, achievements, and league tier are visible to friends. Other users see only your name and avatar."*
- **Delete account:** at the bottom, plainly labeled, two-tap confirmation

### 5.13 App-store review prompts

We don't write the prompt text — the native APIs do that. We control *when* we ask the OS to consider showing the prompt. See [spec §2.6](./product-spec.md#26-app-store-review-prompts) for trigger / never-trigger lists.

**Implementation note (for the engineer):** call `SKStoreReviewController.requestReview(in:)` (iOS) or `ReviewManager.launchReviewFlow(...)` (Android) only after the eligibility gate passes. Do **not** wrap with a pre-prompt sheet ("would you like to rate us?") — that pattern is App Store-discouraged and erodes trust.

---

## 6. Localization principles

V1 ships in English. V1.x / V2 will localize. Constraints to bake in now so localization is cheap later:

- **No idioms in core copy.** "On fire," "kicking it," "wheelin' and dealin'" — none of it. These cost translators days and rarely land.
- **Bot names are the most culturally bound surface.** When we localize, names get a per-locale table. Personalities stay constant; surface names change. Document in `bots/names/{locale}.json` when V1.x ships.
- **No copy with embedded numbers like "Get 5,000 chips!"** — number formatting varies by locale. Use a number-formatted variable.
- **"You" should be informal in every locale.** In languages with formal / informal you (German Sie/du, French vous/tu, etc.), pick informal. We're not the tax office.
- **Test in long-string languages.** German is ~30% longer than English on average. UI must survive it without truncation or wrapping disasters.

---

## 7. Voice review checklist (run before shipping any new copy)

- [ ] Does this beg the user? → cut
- [ ] Does this use an exclamation mark? → probably cut it
- [ ] Does this address the user as "champ" / "high roller" / "ace" / "boss"? → cut
- [ ] Could this be 30% shorter? → make it 30% shorter
- [ ] Does this manufacture urgency that isn't real? → cut the urgency framing
- [ ] If something failed, are we blaming the user? → reframe to blame the system, or use neutral passive
- [ ] If this is a moment (achievement, level, league), is the celebration *earned* or *manufactured*?
- [ ] Would the user feel respected or hustled after reading this?
- [ ] Could a Zynga writer have written this? Could a Duolingo writer have written this without thinking? → reconsider
