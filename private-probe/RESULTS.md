# ByteBans 1.1.0 beta candidate verification

The candidate was built from production source revision `70d3daff4b0af93338d95b207ee596c5e52a43fc`. The frozen release JAR is `frozen/release/ByteBans-1.1.0.jar`, SHA-512 `300a632568e37a22bd8d16ef311625f8bc53db9ce87bab4f2571400952b061df5da3e9aabb56142e62360874c0c2d15696b8afb755054608f8e373106eac15a8`. The separate private test plugin has SHA-512 `d6029de86b401b0f350802b1e38a43a025e9b2ae509fe07e9446980f8ed70687978cbfbb95e59353770cd7a984392f1b716dca0b061fd3d3725afeafb6b39a26`; it is absent from the release JAR. The JAR's `plugin.yml` and Maven `pom.properties` both report `1.1.0`.

All **30/30 pinned Paper targets passed** with this exact JAR: fifteen Minecraft versions in both standalone online- and offline-mode servers. Each was bound to local port 27240, allocated 4 GB and four CPU cores, and stopped after the check; its world remains in `testing/bytebans/live/`. The test plugin exercised ByteBans through synthetic Bukkit login, chat and movement events, including IP ban and mute scope and operator bypass, plus freeze behavior. The Paper build SHA-256 and Java executable are pinned in `matrix.json`; each receipt confirms the deployed plugin hashes, startup diagnostics and stopped state. Evidence is in ignored `runs/20261001T233908Z-local/`, `runs/20261001T234037Z-local/` and `runs/20261001T235211Z-local/`; `runs/summary-300a632568e3.md` and `runs/release-dry-run.json` index the verified target receipts.

| Minecraft | Paper build | Java | Online server | Offline server |
| --- | ---: | ---: | --- | --- |
| 1.21 | 130 | 21 | pass | pass |
| 1.21.1 | 133 | 21 | pass | pass |
| 1.21.3 | 83 | 21 | pass | pass |
| 1.21.4 | 232 | 21 | pass | pass |
| 1.21.5 (experimental Paper build) | 114 | 21 | pass | pass |
| 1.21.6 | 48 | 21 | pass | pass |
| 1.21.7 | 32 | 21 | pass | pass |
| 1.21.8 | 60 | 21 | pass | pass |
| 1.21.9 (experimental Paper build) | 59 | 21 | pass | pass |
| 1.21.10 | 130 | 21 | pass | pass |
| 1.21.11 | 132 | 21 | pass | pass |
| 26.1.1 (experimental Paper build) | 29 | 25 | pass | pass |
| 26.1.2 | 74 | 25 | pass | pass |
| 26.2 | 129 | 25 | pass | pass |
| 26.3 (experimental Paper build) | 140 | 25 | pass | pass |

Real offline-mode Minecraft TCP clients separately passed all seven login/enforcement checks on pinned Paper 1.21, 1.21.4 and 1.21.11: join, IP-ban kick, shared-address rejection, unban and rejoin, IP mute, freeze and unfreeze. These were actual loopback client connections; the server-side position probe checked authoritative movement. Their stopped-server receipts are `runs/20261002T001211Z-local-client-1.21/result.json` (SHA-256 `390bf42418c93c0c82328fd196877bf267fa617a4601433e8cb54a5c3dd5a8a6`), `runs/20261002T001350Z-local-client-1.21.4/result.json` (`ce54e055889ec6ac4223b21bf0dda7d0c01a2fbc5daa4139c2a0c96f890d9799`) and `runs/20261002T001516Z-local-client-1.21.11/result.json` (`43bde18f6d076a129b3b70464da58be57d6496dc035ee3357598af8396fc6168`). No authenticated online-mode account session was available, so online-mode real-player login is **not** proven by the synthetic probes.

The optional admin page also passed seven HTTP/SQLite checks against this JAR on Paper 1.21.4: loopback sign-in page, rejected foreign Host, rejected wrong token, accepted correct token, rejected CSRF without a database write, note creation with an audit actor, and note undo with an audit entry. `runs/20261002T001650Z-local-admin-web/result.json` has SHA-256 `a082215d8c0acdfdf297888040cdfd7aea0e7b52a5616f4af927915304b5b3fd`. The test panel bound to `127.0.0.1:27247`; the server stopped and its configuration was restored to disabled. POSIX `0600` token-file permissions were not rechecked on Windows; they passed on the older snapshot's Linux test, which is separate historical evidence.

Purpur boundary checks passed **4/4** on this exact JAR: pinned 1.21 build 2284 and 1.21.11 build 2568, each in standalone online and offline mode. The receipts are in `runs/20261002T001802Z-purpur/`. They do not establish support for every Purpur version, and no direct Bukkit or Spigot build was tested. The proposed 1.1.0 compatibility metadata therefore lists **Paper only** and exactly the fifteen Paper versions above.

`python private-probe/release_gate.py --dry-run` now recognizes all fifteen Paper versions and the required new-hash client and web checks. Its remaining blocker is the owner's truthful AI eligibility, visibility and Modrinth disclosure review. No ByteBans 1.1.0 upload has been made; the existing public 1.0.0 version is unchanged. Previous `1.1.0-SNAPSHOT` results remain in [RESULTS-SNAPSHOT.md](RESULTS-SNAPSHOT.md) and the ignored `runs/` history and do not qualify this JAR.
