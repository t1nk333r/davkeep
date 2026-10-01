# dav-provider-android

A standalone Android CalDAV/CardDAV sync provider with per-account custom HTTP
headers and client-certificate (mTLS) support. See `README.md` for the problem
statement and scope, and `prompt.md` (untracked, local only) for the environment
specifics and acceptance criteria.

## Versioning

**Every build that produces an APK bumps the version**, in the same change that made
the build worth producing. Both fields live in `app/build.gradle.kts`:

- `versionCode` increases by one, always. Android refuses to install an APK whose
  versionCode is lower than what is already on the device, so it only ever goes up —
  never renumber, never reuse.
- `versionName` follows `major.minor.patch`: patch for a build, minor when a milestone
  lands, major never so far.

The reason is the phone. Several builds a day land on a real device, and a version that
does not move makes "which build is this?" unanswerable from the device itself. The app
shows its own version at the bottom of the settings screen for exactly that check.

## Releasing

A release goes to two places from the same signed APK: the GitHub release, which
Obtainium follows, and the owner's Feather store, an F-Droid repository signed with
the release key. The store's address, an API token and its MCP server
(`feather_mcp.py`) live in `~/.config/feather/` on the release machine.

- Publish with `publish_app`, and always pass `whats_new` with the contents of
  `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`. A version's notes
  are set when it is published and cannot be added later, because the store never
  replaces a version.
- Read the `warnings` in the result. A debuggable APK is published anyway, only with
  a warning.
- After the index rebuild, `repo_status` must show the new version and an empty
  `rejected` list.
- App details (summary, licence, links) change through `update_app`, not through a
  publish. A publish only sets them when it creates the app.

## Agent skills

### Issue tracker

Issues and specs live as GitHub issues in `t1nk333r/dav-provider-android`, via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

The five canonical triage roles, used verbatim as GitHub label names. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` plus `docs/adr/` at the repo root. See `docs/agents/domain.md`.
