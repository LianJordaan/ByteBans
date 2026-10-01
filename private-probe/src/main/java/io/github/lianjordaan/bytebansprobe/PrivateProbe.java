package io.github.lianjordaan.bytebansprobe;

import com.google.gson.GsonBuilder;
import io.github.lianjordaan.byteBans.ByteBans;
import io.github.lianjordaan.byteBans.model.PunishmentData;
import io.github.lianjordaan.byteBans.model.Result;
import io.github.lianjordaan.byteBans.punishments.PunishmentsHandler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/** Internal event simulation. This plugin is separate from the releasable ByteBans JAR. */
public final class PrivateProbe extends JavaPlugin {
    private ByteBans byteBans;
    private PunishmentsHandler handler;
    private final Map<String, Object> results = new LinkedHashMap<>();

    @Override
    public void onEnable() {
        byteBans = (ByteBans) getServer().getPluginManager().getPlugin("ByteBans");
        if (byteBans == null || !byteBans.isEnabled()) {
            getLogger().severe("ByteBans must be enabled before the private probe");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        handler = byteBans.getPunishmentsHandler();
        getCommand("bbprobe").setExecutor(this::command);
        getServer().getScheduler().runTaskLaterAsynchronously(this, this::runProbe, 40);
    }

    private boolean command(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player) {
            sender.sendMessage("This private probe command is console-only.");
            return true;
        }
        if (args.length != 2 || !"position".equalsIgnoreCase(args[0])) {
            sender.sendMessage("Usage: /bbprobe position <online-player>");
            return true;
        }
        Player player = Bukkit.getPlayerExact(args[1]);
        if (player == null) {
            sender.sendMessage("BBPROBE {\"online\":false}");
            return true;
        }
        Location at = player.getLocation();
        PunishmentData frozen = handler.isPlayerFrozen(player.getUniqueId().toString());
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("online", true);
        snapshot.put("x", at.getX());
        snapshot.put("y", at.getY());
        snapshot.put("z", at.getZ());
        snapshot.put("op", player.isOp());
        snapshot.put("bypass", handler.hasPunishmentBypass(player));
        snapshot.put("freeze_id", frozen == null ? null : frozen.getId());
        sender.sendMessage("BBPROBE " + new GsonBuilder().create().toJson(snapshot));
        return true;
    }

    private void runProbe() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("probe_revision", "1");
        document.put("timestamp", Instant.now().toString());
        document.put("minecraft", Bukkit.getMinecraftVersion());
        document.put("server", Bukkit.getVersion());
        document.put("java", System.getProperty("java.version"));
        document.put("bytebans_version", byteBans.getPluginMeta().getVersion());
        check("ip_ban_login_and_operator_bypass", this::ipBanLogin);
        check("ip_ban_scope_exclusion", this::ipBanScope);
        check("ip_mute_chat_and_operator_bypass", this::ipMuteChat);
        check("freeze_movement_and_operator_bypass", this::freezeMovement);
        document.put("cases", results);
        document.put("passed", results.values().stream().allMatch(value -> Boolean.TRUE.equals(value)));
        try {
            Files.createDirectories(getDataFolder().toPath());
            Files.writeString(getDataFolder().toPath().resolve("results.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(document) + "\n", StandardCharsets.UTF_8);
            getLogger().info("Private probe complete: " + results);
        } catch (Exception error) {
            getLogger().severe("Could not write private probe results: " + error);
        }
    }

    private void check(String name, CheckedAction action) {
        try {
            action.run();
            results.put(name, true);
        } catch (Exception error) {
            results.put(name, error.toString());
            getLogger().severe(name + " failed: " + error);
        }
    }

    private void ipBanLogin() throws Exception {
        String address = "198.51.100.81";
        Player ordinary = simulatedPlayer("IpBanRegular", address, false);
        Player operator = simulatedPlayer("IpBanOperator", address, true);
        long id = create("IP", address, "ipban", "*");
        try {
            PlayerLoginEvent denied = login(ordinary, address);
            require(denied.getResult() == PlayerLoginEvent.Result.KICK_OTHER,
                    "IP-banned non-staff login was allowed");
            PlayerLoginEvent bypassed = login(operator, address);
            require(bypassed.getResult() == PlayerLoginEvent.Result.ALLOWED,
                    "operator did not bypass IP ban");
        } finally {
            undo("IP", address, "ipban", id, "ipunban");
        }
        require(login(ordinary, address).getResult() == PlayerLoginEvent.Result.ALLOWED,
                "IP unban did not restore login");
    }

    private void ipBanScope() throws Exception {
        String address = "198.51.100.82";
        Player ordinary = simulatedPlayer("ScopeRegular", address, false);
        long id = create("IP", address, "ipban", "some-other-server");
        try {
            require(login(ordinary, address).getResult() == PlayerLoginEvent.Result.ALLOWED,
                    "IP ban from another scope applied here");
        } finally {
            undo("IP", address, "ipban", id, "ipunban");
        }
    }

    private void ipMuteChat() throws Exception {
        String address = "198.51.100.83";
        Player ordinary = simulatedPlayer("IpMuteRegular", address, false);
        Player operator = simulatedPlayer("IpMuteOperator", address, true);
        String ordinaryId = ordinary.getUniqueId().toString();
        String operatorId = operator.getUniqueId().toString();
        byteBans.setOnlineAddress(ordinaryId, address);
        byteBans.setOnlineAddress(operatorId, address);
        long id = create("IP", address, "ipmute", "*");
        try {
            require(chat(ordinary).isCancelled(), "IP-muted non-staff chat was delivered");
            require(!chat(operator).isCancelled(), "operator did not bypass IP mute");
        } finally {
            undo("IP", address, "ipmute", id, "ipunmute");
        }
        require(!chat(ordinary).isCancelled(), "IP unmute did not restore chat");
        byteBans.clearOnlineAddress(ordinaryId);
        byteBans.clearOnlineAddress(operatorId);
    }

    private void freezeMovement() throws Exception {
        String address = "198.51.100.84";
        Player ordinary = simulatedPlayer("FreezeRegular", address, false);
        Player operator = simulatedPlayer("FreezeOperator", address, true);
        String subject = ordinary.getUniqueId().toString();
        long id = create("PLAYER", subject, "freeze", "*");
        try {
            PlayerMoveEvent held = movement(ordinary);
            require(held.getTo().getX() == 1.0 && held.getTo().getYaw() == 90.0f,
                    "freeze did not hold position while preserving look direction");
            long operatorId = create("PLAYER", operator.getUniqueId().toString(), "freeze", "*");
            try {
                require(movement(operator).getTo().getX() == 2.0,
                        "operator did not bypass freeze");
            } finally {
                undo("PLAYER", operator.getUniqueId().toString(), "freeze", operatorId, "unfreeze");
            }
        } finally {
            undo("PLAYER", subject, "freeze", id, "unfreeze");
        }
        require(movement(ordinary).getTo().getX() == 2.0, "unfreeze did not restore movement");
    }

    private long create(String subjectType, String subject, String type, String scope) {
        require(handler.punishSubject(subjectType.equals("IP") ? "IP" : subject, subjectType,
                subject, "CONSOLE", type, "private probe", scope, 0, true, true),
                "could not create " + type);
        return handler.history(subjectType, subject).stream()
                .filter(row -> row.getType().equals(type)).mapToLong(PunishmentData::getId)
                .max().orElseThrow();
    }

    private void undo(String subjectType, String subject, String type, long id, String undoType) {
        Result result = handler.deactivateSubject(subjectType, subject, type, id,
                "CONSOLE", "private probe cleanup", undoType);
        require(result.isSuccess(), result.getMessage());
    }

    private PlayerLoginEvent login(Player player, String address) throws Exception {
        return onMain(() -> {
            InetAddress ip = InetAddress.getByName(address);
            PlayerLoginEvent event = new PlayerLoginEvent(player, "probe.example", ip);
            Bukkit.getPluginManager().callEvent(event);
            return event;
        });
    }

    private AsyncPlayerChatEvent chat(Player player) {
        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(true, player, "private probe", Set.of());
        Bukkit.getPluginManager().callEvent(event);
        return event;
    }

    private PlayerMoveEvent movement(Player player) throws Exception {
        return onMain(() -> {
            World world = Bukkit.getWorlds().getFirst();
            PlayerMoveEvent event = new PlayerMoveEvent(player,
                    new Location(world, 1, 65, 1, 0, 0),
                    new Location(world, 2, 65, 1, 90, 20));
            Bukkit.getPluginManager().callEvent(event);
            return event;
        });
    }

    private Player simulatedPlayer(String name, String address, boolean operator) throws Exception {
        UUID uuid = UUID.nameUUIDFromBytes(("bytebans-probe:" + name).getBytes(StandardCharsets.UTF_8));
        InetSocketAddress socket = new InetSocketAddress(InetAddress.getByName(address), 25565);
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getName", "getDisplayName" -> name;
                    case "getAddress" -> socket;
                    case "isOp" -> operator;
                    case "hasPermission" -> false;
                    case "isOnline", "isValid" -> true;
                    case "getServer" -> Bukkit.getServer();
                    case "getWorld" -> Bukkit.getWorlds().getFirst();
                    case "getLocation" -> Bukkit.getWorlds().getFirst().getSpawnLocation();
                    case "toString" -> "PrivateProbePlayer(" + name + ")";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return '\0';
        return null;
    }

    private <T> T onMain(Callable<T> operation) throws Exception {
        return Bukkit.getScheduler().callSyncMethod(this, operation).get(5, TimeUnit.SECONDS);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }
}
