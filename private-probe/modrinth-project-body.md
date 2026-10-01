I made ByteBans because I wanted punishments to work across my servers without giving up quick commands and useful tab completion. It keeps bans, mutes, kicks, warnings, staff notes, freezes, and history in SQLite or MySQL. You can target one named server, a set of servers, or the whole network.

## Getting started

Put the JAR for your Paper version in `plugins/` and start the server. A new installation uses a local SQLite database, so you do not need to set up MySQL first. Give each server a distinct `server.name` in `plugins/ByteBans/config.yml`. If you want to share punishments across servers, point them at the same MySQL database. Back up an existing database before upgrading.

Commands use `key:value` arguments and suggest keys, player names, scopes, and known record IDs as you type. For example:

```text
/tempban user:Steve time:1d reason:Griefing scope:*
/ipmute user:Steve time:1h reason:Spam
/history user:Steve page:1
/freeze user:Steve reason:Investigation
```

IP bans and mutes can affect other people on the same network. They use the address your server sees, so configure real-IP forwarding on a proxy before relying on them. Grant IP moderation permissions only to staff you trust. Players must have joined before commands can resolve them by name.

## Optional integrations

Discord notifications and the local admin page are **off by default**. If you enable Discord notifications and enter your own webhook URL, ByteBans sends moderation actions and reasons to that Discord channel. IP punishments can include IP addresses. Staff notes are excluded unless you enable them separately. Keep the webhook and its channel private.

The admin page lets staff search history and take moderation actions. It listens on `127.0.0.1` and needs its generated access token; use a local browser or an SSH tunnel. Do not publish its port directly to the internet. See the [README](https://github.com/LianJordaan/ByteBans#readme) for setup, commands, permissions, and upgrade details.

The new 1.1.0 beta is listed only for the Paper versions verified on its version page. Earlier 1.0.0 files keep their existing compatibility labels.

---

[ByteBuilders Hosting](https://billing.bytebuilders.co.za/aff/BF20) is an affiliate link from the project author.
