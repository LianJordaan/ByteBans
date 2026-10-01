package io.github.lianjordaan.byteBans.listeners;

import io.github.lianjordaan.byteBans.ByteBans;
import io.github.lianjordaan.byteBans.punishments.PunishmentsHandler;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/** Prevents movement while a scoped freeze is active, while allowing players to look around. */
public class FreezeListener implements Listener {
    private final PunishmentsHandler handler;

    public FreezeListener(ByteBans plugin) {
        handler = plugin.getPunishmentsHandler();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || (event.getFrom().getX() == to.getX()
                && event.getFrom().getY() == to.getY()
                && event.getFrom().getZ() == to.getZ())) return;
        if (handler.isPlayerFrozen(event.getPlayer().getUniqueId().toString()) == null
                || handler.hasPunishmentBypass(event.getPlayer())) return;
        Location held = event.getFrom().clone();
        held.setYaw(to.getYaw());
        held.setPitch(to.getPitch());
        event.setTo(held);
    }
}
