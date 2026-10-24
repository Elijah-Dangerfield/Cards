# Ad platform API access

The `/ad-report` skill reads each ad manager through the browser today. APIs would let it pull numbers without the browser. This page says which ones are worth applying for, and has answers drafted for the forms. You submit them; each needs your login and your acceptance of the platform's terms.

Checked October 2026.

## Short version

| Platform | Apply? | Why |
|---|---|---|
| **TikTok** | **Yes, now** | It's your live channel, the form is self-serve, and review is about 2 to 3 business days. |
| Reddit | No | No public sign-up. Ads API access is allow-listed by Reddit's sales team, usually for bigger spenders. Use a CSV export instead. |
| Meta (Instagram) | Later | No ads have run from your Meta login yet. Set it up once you run one. Reading your own ad accounts needs no app review. |
| App Store Connect | Later | You already have an API key (fastlane uses it). Apple's Analytics Reports API can replace the browser for installs, but it isn't confirmed to break installs out by campaign token, so it needs a test first. |

## TikTok: API for Business

1. Go to the [TikTok API for Business portal](https://business-api.tiktok.com/portal) and sign in with the same TikTok for Business login as Ads Manager.
2. Register as a developer, then **My Apps → Create** ([TikTok's guide](https://business-api.tiktok.com/portal/docs/create-a-developer-app/v1.3)).
3. Fill in the form with the answers below, pick the scopes, and submit. Status shows under My Apps → Verification Status.
4. When it's approved, tell me. I'll do the one-time authorization with you and store the long-lived token in a gitignored file, never in the repo.

**Drafted answers**

- **App name:** Downcard Ads Reporting
- **Company:** Nightjar Labs LLC
- **Website / advertiser redirect URL:** `https://downcard.app/`
- **Description:**
  > Internal reporting tool for Nightjar Labs LLC, the developer of Downcard, a free poker app. It reads campaign, ad group and ad performance (spend, impressions, clicks, video view metrics) for our own TikTok ad account only, so we can review results alongside other channels in a private dashboard. It does not create, edit or manage ads, and it does not access any other advertiser's data.
- **Scopes:** reporting and read-only ad account / campaign data only. Scope names change. Pick whatever the [permission scope page](https://business-api.tiktok.com/portal/docs/permission-scope/v1.3) calls reporting plus ad account and ad management *read*. Skip anything that writes, and skip "TikTok Accounts", which now needs an extra application form.

## Reddit

There's no form to fill. Reddit's help page says commercial Ads API use starts by contacting them, and access is granted case by case ([Reddit help](https://support.reddithelp.com/hc/en-us/articles/14945211791892-Developer-Platform-Accessing-Reddit-Data), [Unified.to guide](https://unified.to/blog/how_to_get_your_reddit_ads_api_key)). At your spend it's unlikely to be approved, and it isn't needed:

**What to do instead:** Reddit Ads → Reports → lifetime, ad level → Export CSV, and drop the file in `marketing/ads/data/imports/reddit/`. The skill reads it from there. It also has to work this way because the browser extension blocks reddit.com entirely.

If you ever want to ask anyway, this is enough:
> Hi, I run ads for Downcard (Nightjar Labs LLC) from my own Reddit Ads account. I'd like read-only Ads API access (adsread scope) to pull my own campaign reporting into an internal dashboard. No campaign management or third-party data.

## Meta (Instagram)

Hold off until an ad has actually run from your Meta login. The ad account at your personal login has no history, and the business portfolio's 6 ad accounts don't grant you access. When you're ready:

1. [developers.facebook.com](https://developers.facebook.com/) → Create app → type **Business**, and add the **Marketing API** product.
2. In Business Settings, create a **system user**, give it your ad account with read access, and generate a token with `ads_read`.

Reading your own ad accounts works at standard access, without app review.
