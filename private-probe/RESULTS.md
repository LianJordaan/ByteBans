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

All **28/28 pinned Purpur targets passed** with the same frozen JAR: fourteen versions in standalone online and offline mode. Each used 4 GB, four CPU cores, local port 27244 and its pinned Java runtime; every server stopped and its world remains under `testing/bytebans/live/`. The exact Purpur builds and hashes are in `purpur-matrix.json` (SHA-256 `71dd968ef6f61067032453d711f8cf1b86e732b8d1ffccb87238bbf28c402bc9`). Receipts are in `runs/20261002T001802Z-purpur/` and `runs/20261002T024637Z-purpur/`.

| Minecraft | Purpur build | Java | Online server | Offline server |
| --- | ---: | ---: | --- | --- |
| 1.21 | 2284 | 21 | pass | pass |
| 1.21.1 | 2329 | 21 | pass | pass |
| 1.21.3 | 2358 | 21 | pass | pass |
| 1.21.4 | 2416 | 21 | pass | pass |
| 1.21.5 | 2450 | 21 | pass | pass |
| 1.21.6 | 2465 | 21 | pass | pass |
| 1.21.7 | 2477 | 21 | pass | pass |
| 1.21.8 | 2497 | 21 | pass | pass |
| 1.21.9 | 2505 | 21 | pass | pass |
| 1.21.10 | 2535 | 21 | pass | pass |
| 1.21.11 | 2568 | 21 | pass | pass |
| 26.1.2 | 2592 | 25 | pass | pass |
| 26.2 | 2633 | 25 | pass | pass |
| 26.3 | 2642 | 25 | pass | pass |

Real offline-mode Minecraft TCP clients separately passed login, IP ban, IP mute, freeze and unfreeze on Purpur 1.21 and 1.21.11. Their receipts are `runs/20261002T031322Z-local-purpur-client-1.21/result.json` and `runs/20261002T031500Z-local-purpur-client-1.21.11/result.json`. An earlier 1.21 attempt is retained at `runs/20261002T031007Z-local-purpur-client-1.21/`: its post-login test teleport triggered Purpur's anti-flying kick before the mute check. Moving the marked flat area to world spawn before the client joined resolved that fixture issue; no ByteBans production code or JAR changed. Purpur 26.2 also passed seven authenticated admin-page HTTP/SQLite checks (`runs/20261002T031652Z-local-purpur-admin-web/result.json`). No Microsoft-authenticated online-mode client session was available, so the synthetic online-mode runs do not prove real-account login. No direct Bukkit or Spigot build was tested.

The proposed Modrinth metadata uses two byte-distinct JARs because loaders and Minecraft versions form a Cartesian product there: the primary JAR covers fourteen shared Paper/Purpur versions, and the Paper-only 26.1.1 companion differs only in its manifest. All 2,136 entries were compared, and the exact companion separately passed online- and offline-mode Paper 26.1.1 probes on loopback servers. The official Purpur catalog returns no 26.1.1 build. `python private-probe/release_gate.py --dry-run` verifies all 58 primary live-server receipts, both companion receipts, the five real-client and two admin boundary runs, and the runtime-entry attestation. It is ready for **unlisted** publication. Public listing still requires a truthful human eligibility review; project-page and disclosure checks run immediately before any upload. No ByteBans 1.1.0 upload has been made; the existing public 1.0.0 version is unchanged. Previous `1.1.0-SNAPSHOT` results remain in [RESULTS-SNAPSHOT.md](RESULTS-SNAPSHOT.md) and the ignored `runs/` history and do not qualify this JAR.
