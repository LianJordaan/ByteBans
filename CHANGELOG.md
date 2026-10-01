# Changelog

## 1.1.0-SNAPSHOT (unreleased)

- Use SQLite for new installations while preserving the storage choice in existing configuration files.
- Migrate existing punishment rows in place; add versioned subject and last-known-address storage.
- Add IP bans and mutes, warnings, notes, history and player freezes with matching undo commands.
- Add explicit operator permissions for the new commands, and keep database operations off the main game thread.
- Synchronize database access, close connections on shutdown and keep Bukkit player operations on the main thread.
- Add automated migration, permission and gameplay-enforcement tests.
- Add optional outbound Discord webhook audit notifications, disabled by default.
- Add an optional loopback-only admin page for history and moderation actions, with token sign-in and CSRF protection.
- Keep permanent history deletion console-only, including RCON, and make undo actions atomic with their audit rows.

This candidate has not been uploaded to Modrinth. The published 1.0.0 release is unchanged.
