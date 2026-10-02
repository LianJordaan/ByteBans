# ByteBans

ByteBans keeps bans, mutes, kicks and other moderation records in SQLite or MySQL. Punishments can apply to one server, several named servers or every server in a shared database. The current source builds against Paper 1.21 with Java 21. The frozen `1.1.0` beta JAR passed live checks on fifteen Paper and fourteen Purpur Minecraft versions; [the test record](private-probe/RESULTS.md) lists exact builds and limits. Use Java 21 for the tested 1.21.x servers and Java 25 for the tested 26.x servers. Bukkit and Spigot have not been verified for this JAR.

## Install

Build with JDK 21 and `mvn package`, then place `target/ByteBans-1.1.0.jar` in the server's `plugins` folder. The bundled configuration uses a local SQLite database by default, so a fresh installation needs no database account. Start once to create `plugins/ByteBans/config.yml`, then set a meaningful `server.name`. Leave the default `punishments.default_scope: "*"` if new punishments should apply everywhere.

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
| `/removepunishment` | `/removepunishment id:42` | Permanently delete a record from console or RCON only; use sparingly |

Use `id:<number>` with an undo command when the target has multiple active records of that type. Undo commands keep an audit entry. `time:` accepts `s`, `m`, `h`, `d`, `w`, `mo` and `y`, with a maximum of ten years on the new commands. Scope values can be a server name, `*`, a wildcard such as `survival*`, or a comma-separated set. Warnings and freezes may be timed; notes are permanent until removed. Legacy commands also accept `-s` to keep a punishment announcement private.

Player names are resolved from this server's known player records. A player must have joined the server before a `user:<name>` command can target them. For IP commands, `user:<name>` requires a previously recorded address; use `ip:<literal address>` for an address that has not been seen here.

## Development

Run `mvn test` for migration and enforcement checks, and `mvn package` for the shaded JAR. The test suite covers upgrading a legacy SQLite database, fresh address records, scope and expiration lookup, IP login denial, IP chat muting, freeze movement and command permission denial. A two-server Paper 1.21.4 check also verified scoped/global punishment propagation through an isolated MariaDB database, real offline-client enforcement, history, undo, and restart persistence. [The exact-JAR test record](private-probe/RESULTS.md) separates that one-version network check from the wider Paper/Purpur compatibility matrix; a production MySQL network and Microsoft-authenticated clients were not tested.

## Optional Discord notifications

Create a webhook in a **private staff channel**, then set `discord.enabled: true` and paste its URL into `discord.webhook_url` in `plugins/ByteBans/config.yml`. Restart to apply the setting. ByteBans sends one outbound message when this server saves a moderation record; it cannot receive Discord commands. It never logs the webhook URL. Notifications are best effort: a webhook outage does not undo a saved punishment. Notes and note removals are excluded unless you set `discord.include_notes: true`. Keep the channel and config file private because moderation reasons and IP addresses may appear there.

## Optional local admin page

Set `admin_web.enabled: true` in the config and restart. The panel listens only on `127.0.0.1` at port `8765` by default. On first enable, it writes a random access token to `plugins/ByteBans/admin-web-token.txt`. Keep that file private. Open `http://127.0.0.1:8765` on the server itself, or use an SSH tunnel with the same local port, for example `ssh -L 8765:127.0.0.1:8765 user@your-server`, then open that address on your own computer. A plugin running inside Docker binds to the container's loopback, so arrange a tunnel into that container's network namespace if you need browser access; publishing a Docker port alone will not reach its loopback listener.

Sign in with the token and a short admin name. The name is recorded as `WEB:<name>` on actions; it identifies the label the token holder entered, not a separate authenticated Minecraft account. Anyone who can read the token has full web moderation authority. The page lists and searches history, creates bans, mutes, kicks, IP bans/mutes, warnings, notes and freezes, and offers an **Undo** control on active records. Player targets accept a known name or UUID; IP actions accept an address or `user:<known name>` to use the last recorded address. Undo acts on the selected record ID and keeps an audit record. Sessions expire after an hour. Browser forms use Origin and CSRF checks, and the page accepts connections only from local hostnames. Leave this feature disabled if you do not need it.
