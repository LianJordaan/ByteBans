# ByteBans

ByteBans keeps bans, mutes, kicks and other moderation records in SQLite or MySQL. Punishments can apply to one server, several named servers or every server in a shared database. The current development source builds against Paper 1.21 with Java 21. This is an unreleased `1.1.0-SNAPSHOT` candidate; compatibility beyond the tested server versions must be checked before publishing.

## Install

Build with JDK 21 and `mvn package`, then place `target/ByteBans-1.1.0-SNAPSHOT.jar` in the server's `plugins` folder. The bundled configuration uses a local SQLite database by default, so a fresh installation needs no database account. Start once to create `plugins/ByteBans/config.yml`, then set a meaningful `server.name`. Leave the default `punishments.default_scope: "*"` if new punishments should apply everywhere.

For a network, set `storage.type: mysql` and supply the same database credentials and `storage.table_prefix` on each server. Each server needs a different `server.name`. Existing configuration files keep their chosen storage type; upgrading does not switch an existing MySQL installation to SQLite. Back up the database before upgrading. The startup migration adds subject fields and a last-known-address table without deleting or renumbering old punishments. MySQL connections use five-second connect and socket timeouts.

The plugin stores the last address of players who join. An IP punishment affects everyone using that address, including people on shared networks. Only grant IP moderation permissions to trusted staff. Proxy deployments must forward the real player IP for IP bans and mutes to work as intended. Operators bypass punishments by default; review `punishments.staff_bypass` when testing with an operator account.

## Commands

Arguments use `key:value` syntax. Reasons may contain spaces until the next recognized key. Tab completion suggests keys, online players, scopes and known punishment IDs. Each command requires the matching `bytebans.<command>` permission; the new moderation permissions default to operators.

| Command | Example | Result |
| --- | --- | --- |
| `/ban`, `/tempban`, `/unban` | `/tempban user:Steve time:1d reason:Griefing scope:*` | Ban or unban a player |
| `/mute`, `/tempmute`, `/unmute` | `/mute user:Steve reason:Spam` | Block a player's chat |
| `/kick` | `/kick user:Steve reason:Warning` | Disconnect a player |
| `/ipban`, `/ipunban` | `/ipban ip:203.0.113.7 reason:Ban evasion` | Ban an address or the last known address of `user:<name>` |
| `/ipmute`, `/ipunmute` | `/ipmute user:Steve time:1h reason:Spam` | Block chat from an address |
| `/warn`, `/unwarn` | `/warn user:Steve reason:Griefing` | Record or remove a warning |
| `/note`, `/removenote` | `/note user:Steve reason:Appeal discussed` | Record or remove a staff note |
| `/freeze`, `/unfreeze` | `/freeze user:Steve reason:Investigation` | Keep a player in place while allowing them to look around |
| `/history` | `/history user:Steve page:1` or `/history ip:203.0.113.7` | Show records, IDs, states and timestamps |
| `/refreshpunishments` | `/refreshpunishments` | Reload punishments from storage |
| `/removepunishment` | `/removepunishment id:42` | Permanently delete a record; use sparingly |

Use `id:<number>` with an undo command when the target has multiple active records of that type. Undo commands keep an audit entry. `time:` accepts `s`, `m`, `h`, `d`, `w`, `mo` and `y`, with a maximum of ten years on the new commands. Scope values can be a server name, `*`, a wildcard such as `survival*`, or a comma-separated set. Warnings and freezes may be timed; notes are permanent until removed. Legacy commands also accept `-s` to keep a punishment announcement private.

Player names are resolved from this server's known player records. A player must have joined the server before a `user:<name>` command can target them. For IP commands, `user:<name>` requires a previously recorded address; use `ip:<literal address>` for an address that has not been seen here.

## Development

Run `mvn test` for migration and enforcement checks, and `mvn package` for the shaded JAR. The test suite covers upgrading a legacy SQLite database, fresh address records, scope and expiration lookup, IP login denial, IP chat muting, freeze movement and command permission denial. Live server compatibility is recorded separately from these automated checks.

Discord integration and an admin web page remain planned work. Neither is in this build.
