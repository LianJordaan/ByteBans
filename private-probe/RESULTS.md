# ByteBans 1.1.0 candidate verification

As of 2026-10-02, the frozen JAR at source revision `bb2e229d581a56549b774aa92e11fefa703a431a` has SHA-512 `00eb2430259370247f16a67624ee917a6e8f94a2a5995a77aab4e0d982010235330e814a0b8d08b9e2f0261be276967bc166ef5066ceccba7b7c0a4a7193e5bc`. The separate private probe JAR has SHA-512 `8d326289e3b410afbf7565b8c41b720552c20e8d2d7996a6a6e43c59ad4d2dce41a213fdd73a0a956c16fbf5c2abe49dabef94926e5c102bfc80f2ed887284dc`. No test helper is bundled into the production JAR.

All **30/30 pinned Paper targets passed**, in both direct online and offline server modes, with a fixed 4 GB maximum heap and four visible CPU cores. Each server ran the exact same candidate and helper, verified the Paper SHA-256 pin, initialized ByteBans cleanly, and passed IP-ban, IP-mute, scope and freeze synthetic Bukkit-event checks. Isolated worlds remain stopped under `testing/bytebans/live/` in this workspace. Detailed receipts and the generated table are at `private-probe/runs/summary-00eb24302593.md` and the corresponding timestamped directories.

| Minecraft | Paper build | Java | Online mode | Offline mode |
| --- | ---: | ---: | --- | --- |
| 1.21 | 130 | 21 | pass | pass |
| 1.21.1 | 133 | 21 | pass | pass |
| 1.21.3 | 83 | 21 | pass | pass |
| 1.21.4 | 232 | 21 | pass | pass |
| 1.21.5 (experimental Paper) | 114 | 21 | pass | pass |
| 1.21.6 | 48 | 21 | pass | pass |
| 1.21.7 | 32 | 21 | pass | pass |
| 1.21.8 | 60 | 21 | pass | pass |
| 1.21.9 (experimental Paper) | 59 | 21 | pass | pass |
| 1.21.10 | 130 | 21 | pass | pass |
| 1.21.11 | 132 | 21 | pass | pass |
| 26.1.1 (experimental Paper) | 29 | 25 | pass | pass |
| 26.1.2 | 74 | 25 | pass | pass |
| 26.2 | 129 | 25 | pass | pass |
| 26.3 (experimental Paper) | 140 | 25 | pass | pass |

Real offline-mode Minecraft TCP clients separately passed actual join, active IP-ban kick, shared-IP rejection, unban, shared-IP mute, freeze and unfreeze on Paper 1.21, 1.21.4 and 1.21.11. The 1.21 and 1.21.11 client receipts are in `runs/20261001T223728Z-local-client-1.21/` and `runs/20261001T224115Z-local-client-1.21.11/`; the earlier 1.21.4 client receipt is in `runs/20261001T203852Z-offline/`. Two failed preliminary local client attempts remain in history: a null field omitted by the helper's JSON caused one harness error, and random spawn terrain blocked an unfreeze movement measurement. The successful 1.21.11 retry used a marked flat area. Neither attempt changed the production JAR.

Separate, limited Purpur boundary probes passed **4/4** synthetic targets: Minecraft 1.21 build 2284 and 1.21.11 build 2568, each in online and offline server modes. Their publisher MD5 and independently pinned SHA-256 values are in `testing/bytebans/purpur-pins.json`, with receipts at `runs/20261001T224243Z-purpur/`. These do not establish full Purpur version coverage. No standalone Bukkit or Spigot runtime was tested.

The online-mode probes demonstrate that the plugin runs on servers configured `online-mode=true`; they do not prove a real Microsoft-authenticated player login. The real Minecraft clients ran only in offline mode. The admin web HTTP/SQLite smoke passed separately on Paper 1.21.4, with its receipt at `runs/20261001T210301Z-admin-web/`.

The dedicated release gate qualifies the fifteen Paper versions above and leaves the planned Modrinth upload blocked on the owner's truthful AI eligibility and content-disclosure review. No version has been published from this candidate.
