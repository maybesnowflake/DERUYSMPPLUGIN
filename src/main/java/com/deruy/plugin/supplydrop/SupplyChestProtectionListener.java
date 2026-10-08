package com.deruy.plugin.supplydrop;
import com.deruy.plugin.DeruyPlugin;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import java.util.List;

/** Rewards live in the registry, not the chest inventory. */
public final class SupplyChestProtectionListener implements Listener {
    private final DeruyPlugin plugin;
    public SupplyChestProtectionListener(DeruyPlugin plugin) { this.plugin = plugin; }
    @EventHandler
    public void onChunk(org.bukkit.event.world.ChunkLoadEvent e) { plugin.getSupplyChestRegistry().reconcileChunk(e.getChunk()); }
    private boolean tracked(Block b) { return plugin.getSupplyChestRegistry().isTracked(b.getLocation()); }
    private String mode() { return plugin.getConfig().getString("supplydrop.chest-destruction", "PROTECT"); }
    private void release(Block block) {
        var items = plugin.getSupplyChestRegistry().consume(block.getLocation());
        block.setType(Material.AIR);
        if (items != null) items.forEach(i -> block.getWorld().dropItemNaturally(block.getLocation().add(.5,.5,.5),i));
    }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onBreak(BlockBreakEvent e) {
        if (tracked(e.getBlock()) && mode().equalsIgnoreCase("PROTECT")) {
            e.setCancelled(true); e.getPlayer().sendMessage("§e보급상자는 우클릭으로 수령하세요.");
        }
    }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onBreakDrop(BlockBreakEvent e) {
        if (!tracked(e.getBlock()) || !mode().equalsIgnoreCase("DROP")) return;
        e.setDropItems(false); release(e.getBlock());
    }
    private void protectExplosion(List<Block> blocks) {
        if (mode().equalsIgnoreCase("PROTECT")) blocks.removeIf(this::tracked);
    }
    private void dropExplosion(List<Block> blocks) {
        if (mode().equalsIgnoreCase("DROP")) {
            for (Block block : List.copyOf(blocks)) if (tracked(block)) release(block);
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onExplosion(EntityExplodeEvent e) { protectExplosion(e.blockList()); }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onBlockExplosion(BlockExplodeEvent e) { protectExplosion(e.blockList()); }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onExplosionDrop(EntityExplodeEvent e) { dropExplosion(e.blockList()); }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onBlockExplosionDrop(BlockExplodeEvent e) { dropExplosion(e.blockList()); }
    private boolean inventoryTracked(org.bukkit.inventory.Inventory inv) {
        var loc = inv.getLocation();
        if (loc != null && plugin.getSupplyChestRegistry().isTracked(loc)) return true;
        if (inv instanceof org.bukkit.inventory.DoubleChestInventory d)
            return inventoryTracked(d.getLeftSide()) || inventoryTracked(d.getRightSide());
        return false;
    }
    @EventHandler(ignoreCancelled=true)
    public void onHopper(InventoryMoveItemEvent e) {
        if (inventoryTracked(e.getSource()) || inventoryTracked(e.getDestination())) e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled=true)
    public void onOpen(InventoryOpenEvent e) { if (inventoryTracked(e.getInventory())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled=true)
    public void onPlace(BlockPlaceEvent e) {
        if (e.getBlockPlaced().getType() != Material.CHEST) return;
        for (var face : List.of(org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH,
                org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST)) {
            if (tracked(e.getBlockPlaced().getRelative(face))) {
                e.setCancelled(true); e.getPlayer().sendMessage("§c보급상자 옆에 상자를 붙일 수 없습니다."); return;
            }
        }
    }
}
