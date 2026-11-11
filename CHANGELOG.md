# Changelog

## 0.4.0 — Useful work and repeatable gameplay tests

Fixed a job-state bug that allowed an agent to promise work while the scheduler saw no active job. Fresh work orders now use Bukkit configuration sections instead of raw maps, so status checks and execution work immediately, before any restart.

- Added account-free `/genecraft work` commands for gathering, strip mining and a fixed starter cabin.
- Added supply withdrawal into the player's real inventory, preserving items when inventory space is limited.
- Fixed floating house foundations and added a visible total for building progress.
- Improved gathering with blocked-route recovery and conservative trunk/canopy checks.
- Added mining checks for unsafe floors and vertical liquid neighbors.
- Routed explicit natural-language orders locally in the bridge, without an account or model call. Open-ended requests still use the model. Local commands preserve unread agent messages.
- Fixed status lookup across worlds.
- Fixed the demo installer’s stale version reference; plugin upgrades back up previous GeneCraft JARs and preserve worlds, settings and EULA choices.
- Added a real Paper/Mineflayer acceptance runner, manual GitHub gameplay checks, and release metadata validation.

The starter cabin is an original fixed blueprint, and work uses bounded server-side rules. Agents do not yet perform unrestricted survival gameplay, learn new skills or reliably execute arbitrary plans. Model reasoning and custom skins are outside the headless acceptance suite.
