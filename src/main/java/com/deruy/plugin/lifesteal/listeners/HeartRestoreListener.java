package com.deruy.plugin.lifesteal.listeners;
import com.deruy.plugin.DeruyPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
public final class HeartRestoreListener implements Listener {
    private final DeruyPlugin plugin;
    public HeartRestoreListener(DeruyPlugin plugin) { this.plugin = plugin; }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) { plugin.getServer().getScheduler().runTask(plugin, () -> apply(plugin, e.getPlayer())); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) { plugin.getServer().getScheduler().runTask(plugin, () -> apply(plugin, e.getPlayer())); }
    public static void apply(DeruyPlugin plugin, Player player) {
        if (!player.isOnline() || player.isDead()) return;
        Double hearts = plugin.getDataStore().getHeartRestore(player.getUniqueId());
        if (hearts == null) return;
        var manager = plugin.getLifeStealManager();
        double restored = Math.min(hearts, manager.getMaxHealth(player)/2);
        if (!manager.setHearts(player, restored, "REVIVE_APPLIED")) {
            plugin.getLogger().warning("부활 하트 적용 실패: " + player.getUniqueId()); return;
        }
        player.setHealth(restored*2);
        manager.clearCombatTag(player.getUniqueId());
        plugin.getDataStore().clearHeartRestore(player.getUniqueId());
        player.sendMessage("§a부활하여 하트 " + restored + "개가 적용되었습니다.");
    }
}
