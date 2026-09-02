#!/usr/bin/env python3
"""
Renders the "what merging this actually does" block for the release-please PR.

Why this exists: the release PR body used to be a fixed string in
release-please-config.json. It claimed the same four things every time
("Play production, 10% staged", "submitted to App Store review"), and those
claims are only true for *some* releases. On 2026-09-01 it read "submitted to
App Store review" while a build was already sitting in review, which is the
one case where merging is a bad idea and the body said nothing about it.

So instead of asserting, this asks:

  * Play    — is there a production release? is a staged rollout still running?
  * ASC     — is a version already in review or waiting to be released?
  * git     — what does this release actually change for a user, given that the
              server ships continuously and is probably already live?

Everything degrades to an honest "could not check" line. A missing credential
must never turn into a confident wrong answer, because the whole point is that
the reader can trust this block more than the boilerplate it replaced.

Writes markdown to stdout. Never exits non-zero: a broken probe should not
block the release PR from being updated.
"""

from __future__ import annotations

import base64
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timezone

TIMEOUT = 30

# Paths whose changes reach a user only through a store release.
CLIENT_PREFIXES = ("apps/compose/", "apps/ios/", "libraries/")
# Paths that ship on the server's own deploy cadence, independent of the app.
SERVER_PREFIXES = ("apps/server/", "apps/admin/")

# App Store Connect states meaning a version is already in flight. Submitting a
# different version while one of these is current is the collision worth
# warning about. PREPARE_FOR_SUBMISSION is deliberately absent: that is the
# normal resting state and `deliver(force: true)` overwrites it happily.
ASC_IN_FLIGHT = {
    "WAITING_FOR_REVIEW",
    "IN_REVIEW",
    "PENDING_DEVELOPER_RELEASE",
    "PENDING_APPLE_RELEASE",
    "PROCESSING_FOR_APP_STORE",
}
# States meaning a submission came back and needs a human before anything else
# can go up.
ASC_NEEDS_ATTENTION = {
    "REJECTED",
    "METADATA_REJECTED",
    "DEVELOPER_REJECTED",
    "INVALID_BINARY",
}


def sh(*args: str) -> str:
    return subprocess.run(
        args, capture_output=True, text=True, check=True
    ).stdout.strip()


@dataclass
class Commit:
    sha: str
    subject: str
    files: list[str] = field(default_factory=list)

    @property
    def touches_client(self) -> bool:
        return any(f.startswith(CLIENT_PREFIXES) for f in self.files)

    @property
    def touches_server(self) -> bool:
        return any(f.startswith(SERVER_PREFIXES) for f in self.files)

    @property
    def conventional_type(self) -> str | None:
        m = re.match(r"^(\w+)(\([^)]*\))?!?:", self.subject)
        return m.group(1) if m else None


# ── git ──────────────────────────────────────────────────────────────────────


def previous_tag() -> str | None:
    tags = sh("git", "tag", "-l", "v*", "--sort=-v:refname").splitlines()
    return tags[0] if tags else None


def commits_since(ref: str | None) -> list[Commit]:
    """Commits in the release range, excluding merge commits.

    Merges are dropped on purpose. GitHub puts the PR title in a merge commit's
    body, so a `develop` -> `main` merge of a conventional-commit-titled PR gets
    counted twice — that is exactly how ENG-47 landed in the 0.2.0 changelog
    two times over.
    """
    span = f"{ref}..HEAD" if ref else "HEAD"
    raw = sh("git", "log", "--no-merges", "--format=%H%x00%s", span)
    if not raw:
        return []
    out = []
    for line in raw.splitlines():
        sha, _, subject = line.partition("\0")
        files = sh(
            "git", "show", "--name-only", "--format=", "--no-renames", sha
        ).splitlines()
        out.append(Commit(sha=sha, subject=subject, files=[f for f in files if f]))
    return out


# ── GitHub ───────────────────────────────────────────────────────────────────


def gh_api(path: str) -> dict:
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not token:
        raise RuntimeError("no GH_TOKEN")
    repo = os.environ.get("GITHUB_REPOSITORY", "")
    req = urllib.request.Request(
        f"https://api.github.com/repos/{repo}{path}",
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
        },
    )
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        return json.loads(r.read())


def last_server_deploy() -> tuple[str, str] | None:
    """(short sha, ISO timestamp) of the newest successful prod server deploy."""
    data = gh_api(
        "/actions/workflows/server-deploy-prod.yml/runs"
        "?status=success&per_page=1&branch=main"
    )
    runs = data.get("workflow_runs") or []
    if not runs:
        return None
    return runs[0]["head_sha"][:8], runs[0]["updated_at"]


# ── Google Play ──────────────────────────────────────────────────────────────


def google_access_token(sa: dict) -> str:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding

    def b64(raw: bytes) -> bytes:
        return base64.urlsafe_b64encode(raw).rstrip(b"=")

    now = int(time.time())
    header = b64(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claim = b64(
        json.dumps(
            {
                "iss": sa["client_email"],
                "scope": "https://www.googleapis.com/auth/androidpublisher",
                "aud": "https://oauth2.googleapis.com/token",
                "iat": now,
                "exp": now + 3600,
            }
        ).encode()
    )
    unsigned = header + b"." + claim
    key = serialization.load_pem_private_key(sa["private_key"].encode(), password=None)
    assertion = unsigned + b"." + b64(key.sign(unsigned, padding.PKCS1v15(), hashes.SHA256()))
    body = urllib.parse.urlencode(
        {
            "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
            "assertion": assertion.decode(),
        }
    ).encode()
    req = urllib.request.Request("https://oauth2.googleapis.com/token", data=body)
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        return json.loads(r.read())["access_token"]


def play_state(package: str) -> str:
    """One markdown line describing where Android will actually land."""
    raw = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", "").strip()
    if not raw:
        return "- **Android**: not checked, `PLAY_SERVICE_ACCOUNT_JSON` is not set."

    token = google_access_token(json.loads(raw))
    base = (
        "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/"
        f"{urllib.parse.quote(package, safe='')}/edits"
    )

    def call(method: str, url: str) -> dict:
        req = urllib.request.Request(
            url, method=method, headers={"Authorization": f"Bearer {token}"}
        )
        if method == "POST":
            req.add_header("Content-Length", "0")
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            payload = r.read()
            return json.loads(payload) if payload else {}

    try:
        edit_id = call("POST", base).get("id")
    except urllib.error.HTTPError:
        edit_id = None
    if not edit_id:
        return (
            "- **Android**: no Play edit could be opened (new app, or the service "
            "account lacks permission). The release will route to the **internal** "
            "track and you must promote it by hand."
        )

    try:
        track = call("GET", f"{base}/{edit_id}/tracks/production")
    except urllib.error.HTTPError:
        track = {}
    finally:
        try:
            call("DELETE", f"{base}/{edit_id}")
        except urllib.error.HTTPError:
            pass

    releases = track.get("releases") or []
    if not releases:
        return (
            "- **Android**: Play has no production release yet, so this goes to the "
            "**internal** track. Promote it to production by hand in Play Console."
        )

    line = (
        "- **Android**: uploads to the Play **production** track at a "
        "**10% staged rollout**."
    )
    rolling = [r for r in releases if r.get("status") == "inProgress"]
    if rolling:
        r = rolling[0]
        pct = r.get("userFraction")
        pct_text = f"{float(pct) * 100:.0f}%" if pct is not None else "an unfinished"
        name = r.get("name") or ", ".join(str(c) for c in r.get("versionCodes") or [])
        line += (
            f"\n  - ⚠️ **{name} is still rolling out at {pct_text}.** Shipping this "
            "supersedes that rollout before it reaches everyone."
        )
    return line


# ── App Store Connect ────────────────────────────────────────────────────────


def asc_token() -> str:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec, utils

    key_id = os.environ["ASC_KEY_ID"]
    issuer = os.environ["ASC_ISSUER_ID"]
    pem = base64.b64decode(os.environ["ASC_KEY_P8_BASE64"])

    def b64(raw: bytes) -> bytes:
        return base64.urlsafe_b64encode(raw).rstrip(b"=")

    now = int(time.time())
    header = b64(json.dumps({"alg": "ES256", "kid": key_id, "typ": "JWT"}).encode())
    claim = b64(
        json.dumps(
            {"iss": issuer, "iat": now, "exp": now + 600, "aud": "appstoreconnect-v1"}
        ).encode()
    )
    unsigned = header + b"." + claim
    key = serialization.load_pem_private_key(pem, password=None)
    der = key.sign(unsigned, ec.ECDSA(hashes.SHA256()))
    # ASC wants the raw r||s pair, not the DER wrapper openssl-style signing gives.
    r, s = utils.decode_dss_signature(der)
    raw_sig = r.to_bytes(32, "big") + s.to_bytes(32, "big")
    return (unsigned + b"." + b64(raw_sig)).decode()


def asc_get(path: str, token: str) -> dict:
    req = urllib.request.Request(
        f"https://api.appstoreconnect.apple.com{path}",
        headers={"Authorization": f"Bearer {token}"},
    )
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        return json.loads(r.read())


def ios_bundle_id() -> str | None:
    """`PRODUCT_BUNDLE_IDENTIFIER` with the `$(TEAM_ID)` suffix resolved."""
    try:
        with open("apps/ios/Configuration/Config.xcconfig") as f:
            text = f.read()
    except OSError:
        return None
    m = re.search(r"^PRODUCT_BUNDLE_IDENTIFIER=(.+)$", text, re.MULTILINE)
    if not m:
        return None
    return m.group(1).strip().replace("$(TEAM_ID)", os.environ.get("APPLE_TEAM_ID", ""))


def ios_state() -> str:
    if not all(os.environ.get(k) for k in ("ASC_KEY_ID", "ASC_ISSUER_ID", "ASC_KEY_P8_BASE64")):
        return "- **iOS**: not checked, App Store Connect API credentials are not set."

    token = asc_token()
    app_id = os.environ.get("ASC_APP_ID", "").strip()

    if not app_id:
        # `PRODUCT_BUNDLE_IDENTIFIER` is an xcconfig interpolation plus a secret,
        # so this string is a guess. On the first live run it produced
        # `com.dangerfield.cards.CardsMSMDV43SUS`, which matches nothing.
        bundle = ios_bundle_id()
        apps = []
        if bundle:
            q = urllib.parse.quote(bundle, safe="")
            apps = asc_get(f"/v1/apps?filter[bundleId]={q}&limit=2", token).get("data") or []

        if not apps:
            candidates = (
                asc_get("/v1/apps?limit=50&fields[apps]=name,bundleId", token).get("data") or []
            )
            named = [
                a for a in candidates
                if "downcard" in str((a.get("attributes") or {}).get("name", "")).lower()
            ]
            apps = candidates if len(candidates) == 1 else named

        if len(apps) != 1:
            # Say what was actually found. A failure that names the candidates is
            # one someone can fix; "matched N apps" is not.
            listing = ", ".join(
                f"{(a.get('attributes') or {}).get('name')} (`{(a.get('attributes') or {}).get('bundleId')}`)"
                for a in (candidates if not apps else apps)
            ) or "no apps visible to this key"
            return (
                "- **iOS**: could not identify the app in App Store Connect. Derived bundle id "
                f"`{bundle}` matched nothing, and the account has: {listing}. "
                "Set the `ASC_APP_ID` secret to the right app id to fix this permanently."
            )
        app_id = apps[0]["id"]
    versions = (
        asc_get(
            f"/v1/apps/{app_id}/appStoreVersions?limit=5"
            "&fields[appStoreVersions]=versionString,appStoreState,createdDate",
            token,
        ).get("data")
        or []
    )

    line = (
        "- **iOS**: uploads to TestFlight group `main`, then submits for App Store "
        "review with phased release."
    )
    for v in versions:
        attrs = v.get("attributes") or {}
        state = attrs.get("appStoreState") or ""
        version = attrs.get("versionString") or "?"
        if state in ASC_IN_FLIGHT:
            line += (
                f"\n  - 🛑 **{version} is already `{state}`.** App Store Connect holds one "
                "version in flight at a time, so this submission will collide with it. "
                "Wait for that build to clear, or remove it from review first."
            )
            break
        if state in ASC_NEEDS_ATTENTION:
            line += (
                f"\n  - ⚠️ **{version} is `{state}`** and needs a human in App Store "
                "Connect before a new version can go up."
            )
            break
    else:
        line += "\n  - ✅ No version is currently in review or awaiting release."
    return line


# ── rendering ────────────────────────────────────────────────────────────────


def guard(fn, label: str) -> str:
    """Run a probe; turn any failure into an honest line instead of a crash."""
    try:
        return fn()
    except Exception as e:  # noqa: BLE001 - a probe must never break the PR update
        print(f"probe failed: {label}: {e!r}", file=sys.stderr)
        return f"- **{label}**: check failed (`{type(e).__name__}`). Verify by hand."


def server_deploy_note() -> str:
    """How the reader should think about the server-only commits."""
    try:
        found = last_server_deploy()
    except Exception as e:  # noqa: BLE001
        print(f"probe failed: server deploy: {e!r}", file=sys.stderr)
        found = None
    if not found:
        return "ship on the server's own deploy cadence, independent of this PR"
    sha, when = found
    return f"already live in prod (last server deploy `{sha}`, {when})"


def package_name() -> str:
    with open("versions.properties") as f:
        m = re.search(r"^applicationId=(.+)$", f.read(), re.MULTILINE)
    return m.group(1).strip() if m else ""


def main() -> int:
    version = os.environ.get("RELEASE_VERSION", "").strip()
    prev = previous_tag()
    commits = commits_since(prev)

    client = [c for c in commits if c.touches_client]
    server_only = [c for c in commits if c.touches_server and not c.touches_client]
    other = [c for c in commits if not c.touches_client and not c.touches_server]

    feats = sum(1 for c in commits if c.conventional_type == "feat")
    fixes = sum(1 for c in commits if c.conventional_type in ("fix", "perf"))

    out: list[str] = ["## What merging this actually does", ""]

    if version:
        since = f"since {prev}" if prev else "in the first release"
        out.append(
            f"**{prev[1:] if prev else 'nothing'} → {version}**, carrying {feats} "
            f"{'feature' if feats == 1 else 'features'} and {fixes} "
            f"{'fix' if fixes == 1 else 'fixes'} {since}."
        )
        out.append("")

    checked = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    out.append(f"### Where it goes _(checked live at {checked})_")
    out.append("")
    out.append(guard(lambda: play_state(package_name()), "Android"))
    out.append(guard(ios_state, "iOS"))
    out.append("- **Sentry**: a release is created and mappings/dSYMs uploaded.")
    out.append("")

    out.append("### What actually changes for users")
    out.append("")
    code = len(client) + len(server_only)
    out.append(
        f"{code} of the {len(commits)} commits {'since ' + prev if prev else ''} touch code "
        f"(the other {len(other)} are docs, CI and tooling)."
    )
    out.append("")
    out.append(
        f"- **{len(client)} touch client code.** These are what merging actually delivers."
    )
    if server_only:
        out.append(
            f"- **{len(server_only)} are server-only** and are {server_deploy_note()}. "
            "They are in the changelog for the record, but merging changes nothing "
            "about them."
        )
    out.append("")
    out.append(
        "**Not ready?** Land a revert on `main` and this PR rewrites itself within a minute."
    )
    print("\n".join(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
