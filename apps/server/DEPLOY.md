# Deploying `cards-server-dev` to Fly.io

The first deploy is a one-time setup. After that, merging to `main` with
changes under `apps/server/**` triggers an auto-deploy via GitHub Actions
(see `.github/workflows/server-deploy.yml`).

## One-time setup

1. **Install flyctl** (if you don't have it):
   ```
   brew install flyctl
   ```

2. **Log in**:
   ```
   fly auth login
   ```
   Opens a browser. Sign up or sign in to your Fly account.

3. **Create the dev app**:
   ```
   fly apps create cards-server-dev
   ```
   (`cards-server` for prod, later.)

4. **Set secrets** (from the repo root):
   ```
   fly secrets set \
     DATABASE_URL='postgresql://postgres:nD58ubv82mzv%24EV@db.yuqrfhdoejonclgbixlw.supabase.co:5432/postgres' \
     SUPABASE_URL='https://yuqrfhdoejonclgbixlw.supabase.co' \
     -a cards-server-dev
   ```
   Notes:
   - `DATABASE_URL` uses Supabase's **direct connection** host (works from Fly because Fly has IPv6 outbound).
   - The `$` in the password is URL-encoded as `%24`.
   - `SUPABASE_URL` is enough on its own: the server fetches the project's public JWT signing keys from `<SUPABASE_URL>/auth/v1/.well-known/jwks.json` at runtime. No shared JWT secret is stored anywhere.
   - For account deletion, add `SUPABASE_SERVICE_ROLE_KEY` here (Project Settings → API → service_role key, treat like a root password).

   **Optional: error reporting via Sentry.**
   Once a Sentry project exists, add its DSN. Without these, the server logs a warning at startup and `Sentry.captureException(...)` becomes a no-op.
   ```
   fly secrets set \
     SENTRY_DSN='<https://...@oXXXX.ingest.sentry.io/YYYY>' \
     SENTRY_ENVIRONMENT='dev' \
     -a cards-server-dev
   ```
   For `cards-server` (prod), use `SENTRY_ENVIRONMENT='prod'` against the same Sentry project. Use one Sentry project for the server (separate from the client project) and let `SENTRY_ENVIRONMENT` differentiate dev vs prod issues — easier cross-env grouping than splitting projects.

5. **Get a deploy token for CI**:
   ```
   fly tokens create deploy -a cards-server-dev --expiry 8760h
   ```
   Copy the output. Paste it into GitHub: Repo → Settings → Secrets and variables → Actions → New repository secret → name `FLY_API_TOKEN_DEV`.

6. **First deploy**:
   ```
   fly deploy --config apps/server/fly.toml --remote-only
   ```
   `--remote-only` uses Fly's builders instead of local Docker (no Docker desktop required). Takes ~3-5 minutes for the first build; subsequent deploys reuse cached layers.

7. **Verify**:
   ```
   curl https://cards-server-dev.fly.dev/_health
   # {"ok":true}
   ```

## Day-to-day

- **Tail logs**: `fly logs -a cards-server-dev`
- **SSH into running VM**: `fly ssh console -a cards-server-dev`
- **List recent deploys**: `fly releases -a cards-server-dev`
- **Roll back**: `fly releases rollback <version> -a cards-server-dev`
- **Rotate a secret**: `fly secrets set KEY=value -a cards-server-dev` (triggers redeploy)

## Cost

- VM: shared-cpu-1x with 256MB RAM. Free tier covers it.
- Idle: scales to zero, so cold cost is ~$0 in dev.
- Bandwidth: free tier covers ~100GB/mo egress; we're not close.
- Realistic dev monthly bill: **$0** while we're on free tier.

## When `cards-server` (prod) ships

Copy `fly.toml` to `apps/server/fly.prod.toml`, edit:
- `app = 'cards-server'`
- `min_machines_running = 1` (no cold-start stalls for real users)
- Possibly `[[vm]] memory = '512mb'` if we see GC pressure

Provision with `fly apps create cards-server` + `fly secrets set ... -a cards-server` (pointing at the **prod** Supabase project's `DATABASE_URL` and `SUPABASE_JWT_SECRET`). Then `fly deploy --config apps/server/fly.prod.toml --remote-only`.
