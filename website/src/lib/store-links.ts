// Store links that carry an ad campaign through to Apple and Google, so each
// store can count installs per campaign.
//
// Apple counts installs for links carrying pt (our provider token) and ct (the
// campaign). Google Play counts them from UTM fields in the referrer param.
// Both are public by design: they appear in every link we hand out.

export const APP_STORE_URL = 'https://apps.apple.com/us/app/downcard-poker-with-friends/id6788423648';
export const PLAY_STORE_URL = 'https://play.google.com/store/apps/details?id=com.dangerfield.cards';

const APP_STORE_PROVIDER_TOKEN = '120164045';

// App Store custom product pages, by the short name ads use (?p=friends).
const PRODUCT_PAGES: Record<string, string> = {
  friends: '1cead2fb-484a-4392-bc69-782d50d1958d',
};

// Apple caps ct at 30 characters; keep the same rule for Play so tags match.
const CAMPAIGN_PATTERN = /^[a-z0-9][a-z0-9_-]{0,29}$/i;

export interface Campaign {
  campaign: string;
  source: string;
  page?: string;
}

export type Platform = 'ios' | 'android' | 'other';

/** Reads ?c= (or ?ct=), ?s= (or ?utm_source=) and ?p= from a query string. */
export function readCampaign(search: string): Campaign | null {
  const params = new URLSearchParams(search);
  const campaign = params.get('c') ?? params.get('ct') ?? '';
  if (!CAMPAIGN_PATTERN.test(campaign)) return null;
  const sourceParam = params.get('s') ?? params.get('utm_source') ?? '';
  const source = CAMPAIGN_PATTERN.test(sourceParam) ? sourceParam : campaign.split(/[-_]/)[0];
  const pageParam = params.get('p') ?? '';
  const page = pageParam in PRODUCT_PAGES ? pageParam : undefined;
  return { campaign, source, page };
}

export function appStoreUrl(tag: Campaign | null): string {
  if (!tag) return APP_STORE_URL;
  const params = new URLSearchParams();
  if (tag.page) params.set('ppid', PRODUCT_PAGES[tag.page]);
  params.set('pt', APP_STORE_PROVIDER_TOKEN);
  params.set('ct', tag.campaign);
  params.set('mt', '8');
  return `${APP_STORE_URL}?${params}`;
}

export function playStoreUrl(tag: Campaign | null): string {
  if (!tag) return PLAY_STORE_URL;
  const referrer = new URLSearchParams({
    utm_source: tag.source,
    utm_medium: 'paid',
    utm_campaign: tag.campaign,
  });
  return `${PLAY_STORE_URL}&referrer=${encodeURIComponent(referrer.toString())}`;
}

/** iPadOS reports a Mac user agent, so touch support breaks the tie. */
export function platformOf(userAgent: string, maxTouchPoints = 0): Platform {
  if (/iPhone|iPad|iPod/.test(userAgent)) return 'ios';
  if (/Macintosh/.test(userAgent) && maxTouchPoints > 1) return 'ios';
  if (/Android/.test(userAgent)) return 'android';
  return 'other';
}

export function storeUrlFor(platform: Platform, tag: Campaign | null): string | null {
  if (platform === 'ios') return appStoreUrl(tag);
  if (platform === 'android') return playStoreUrl(tag);
  return null;
}
