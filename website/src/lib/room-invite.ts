// Room invites: downcard.app/join/<code>. GitHub Pages has no server-side
// routing, so /join/<code> falls through to 404.html, which forwards here as
// /join/?code=<code>. Both shapes are read so a link works either way.

const ROOM_CODE = /^[A-Z0-9]{6}$/;

/** The room code from ?code= or a /join/<code> path, or null if neither holds one. */
export function readRoomCode(search: string, pathname: string): string | null {
  const fromQuery = new URLSearchParams(search).get('code');
  const fromPath = pathname.match(/^\/join\/([^/?#]+)\/?$/)?.[1];
  const raw = (fromQuery ?? fromPath ?? '').trim().toUpperCase();
  return ROOM_CODE.test(raw) ? raw : null;
}

/** The app's own deep link for a room, which LobbyFeatureEntryPoint resolves. */
export function appLinkFor(code: string): string {
  return `cards://join/${code}`;
}

/** Store installs that came from an invite, so they can be counted apart from ads. */
export const INVITE_CAMPAIGN = { campaign: 'invite', source: 'invite', medium: 'referral' };
