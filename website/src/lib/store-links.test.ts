// Run with: node --test src/lib/store-links.test.ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { appStoreUrl, platformOf, playStoreUrl, readCampaign, storeUrlFor } from './store-links.ts';

const IPHONE = 'Mozilla/5.0 (iPhone; CPU iPhone OS 26_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148';
const IPAD_DESKTOP_MODE = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15';
const ANDROID = 'Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36';
const TIKTOK_IOS_WEBVIEW = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 musical_ly_37.0.0 BytedanceWebview/d8a21c6';

test('reads campaign, source from the prefix, and a known product page', () => {
  assert.deepEqual(readCampaign('?c=tiktok-friends15s-v1&p=friends'), {
    campaign: 'tiktok-friends15s-v1',
    source: 'tiktok',
    page: 'friends',
  });
});

test('accepts ct and utm_source aliases', () => {
  assert.deepEqual(readCampaign('?ct=ig-boost-v2&utm_source=instagram'), {
    campaign: 'ig-boost-v2',
    source: 'instagram',
    page: undefined,
  });
});

test('rejects missing, oversized or unsafe campaigns', () => {
  assert.equal(readCampaign(''), null);
  assert.equal(readCampaign('?c=' + 'a'.repeat(31)), null);
  assert.equal(readCampaign('?c=<script>'), null);
});

test('ignores unknown product pages instead of sending a bad ppid', () => {
  assert.equal(readCampaign('?c=reddit-v1&p=nope')?.page, undefined);
});

test('App Store link carries ppid, provider token and campaign', () => {
  const url = new URL(appStoreUrl(readCampaign('?c=tiktok-friends15s-v1&p=friends')));
  assert.equal(url.searchParams.get('ppid'), '1cead2fb-484a-4392-bc69-782d50d1958d');
  assert.equal(url.searchParams.get('pt'), '120164045');
  assert.equal(url.searchParams.get('ct'), 'tiktok-friends15s-v1');
  assert.equal(url.searchParams.get('mt'), '8');
});

test('Play link carries the campaign as UTM fields in the referrer', () => {
  const url = new URL(playStoreUrl(readCampaign('?c=reddit-friends15s-v1')));
  assert.equal(url.searchParams.get('id'), 'com.dangerfield.cards');
  const referrer = new URLSearchParams(url.searchParams.get('referrer') ?? '');
  assert.equal(referrer.get('utm_source'), 'reddit');
  assert.equal(referrer.get('utm_medium'), 'paid');
  assert.equal(referrer.get('utm_campaign'), 'reddit-friends15s-v1');
});

test('untagged links stay plain', () => {
  assert.equal(appStoreUrl(null), 'https://apps.apple.com/us/app/downcard-poker-with-friends/id6788423648');
  assert.equal(playStoreUrl(null), 'https://play.google.com/store/apps/details?id=com.dangerfield.cards');
});

test('detects platforms, including iPad in desktop mode and in-app browsers', () => {
  assert.equal(platformOf(IPHONE), 'ios');
  assert.equal(platformOf(TIKTOK_IOS_WEBVIEW), 'ios');
  assert.equal(platformOf(IPAD_DESKTOP_MODE, 5), 'ios');
  assert.equal(platformOf(IPAD_DESKTOP_MODE, 0), 'other');
  assert.equal(platformOf(ANDROID), 'android');
});

test('desktop gets no store redirect', () => {
  assert.equal(storeUrlFor('other', readCampaign('?c=tiktok-v1')), null);
});
