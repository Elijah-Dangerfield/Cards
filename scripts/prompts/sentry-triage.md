Sentry triage routine.

Weekly scheduled-task prompt. Pull the most impactful unresolved **production** Sentry issues for the app, pick the ones fixable from the codebase, open one pull request per fix.

Tools at your disposal: Sentry MCP (search_issues, search_issue_events, get_event_attachment, etc.), gh CLI (already authed), local file tools, gradle. No API token or curl required — use the MCP.

Procedure:

1. Via the Sentry MCP, list unresolved issues on the production environment from the last 7 days, sorted by event frequency. Cap at 5.

2. For each issue, pull the latest event for stack trace + tags. Skip any issue where:
   - The top in-app frame is in a third-party SDK or system framework you can't edit
   - The fingerprint points to a user-environment problem (network timeout, disk full, bare cancelled coroutine)
   - An open PR already references the issue ID (gh pr list --search "$ISSUE_ID")
   - The issue is already resolved on main (git log --all --grep "$ISSUE_ID")

   For each skipped issue, open a tracking GitHub issue so it stays visible without clogging the auto-merge queue.

3. For each triageable issue:
   - Create branch ai/sentry-<short-issue-id>
   - Read relevant source, form a root-cause hypothesis
   - Apply the smallest plausible fix. Prefer defensive checks at the failure site over rewrites
   - Add a regression test only if the failure is reproducible from a unit test
   - Verify build:
       ./gradlew :apps:compose:compileDebugKotlinAndroid :apps:compose:compileKotlinIosSimulatorArm64
       ./gradlew testDebugUnitTest  (if tests were added)
   - If the build fails and you can't fix it in one more attempt, abandon the branch and open a tracking issue

4. Open one PR per fix:
   - Title: fix: <terse description>  (conventional commit — release-please derives the changelog from this)
   - Body: Sentry link, 1-2 sentence hypothesis of the root cause, what changed
   - Labels: ai-autofix (triggers auto-merge on green CI), sentry

5. End-of-run summary: issues seen, PRs opened, issues skipped with reasons. Post it to your final reply.

Hard limits:
- Never modify anything under .github/workflows. Fixing CI is outside this routine.
- Never bump dependency versions. If a dep is at fault, open a tracking issue instead.
- Never edit versions.properties, CHANGELOG.md, or Config.xcconfig — release-please owns them.
- Never force-push, rebase published branches, or delete branches you did not create.
- If I say "dry run", do everything except gh pr create — print the intended diff and PR body instead.
