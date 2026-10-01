ByteBans 1.1.0 beta adds IP bans and mutes, warnings, staff notes, history lookup, and player freeze/unfreeze commands. New installations use SQLite by default; existing MySQL configurations and punishment records remain supported through a schema migration. Console-only permanent history deletion remains available, and undo actions retain an audit entry.

Optional Discord webhook notifications and a token-protected local admin page are disabled by default. Enabling the webhook sends moderation details, potentially including IP addresses, to the Discord endpoint the server administrator configured. The admin page listens on the server's loopback address and supports moderation actions and history search.

Compatibility metadata for this release is limited to Paper versions that pass the pinned online- and offline-mode server checks with this exact JAR. Online-mode checks simulate Bukkit events; they do not include an authenticated Minecraft account. A real offline-mode client checked login, IP ban, IP mute, freeze, and unfreeze on Paper 1.21.4. No Bukkit, Spigot, or Purpur compatibility is claimed by this release.

Provenance: substantial code in this update and these release notes are AI-generated. The project owner must review eligibility and apply Modrinth's AI-generated code and text disclosures before publication.
