package com.deruy.plugin.randomevent;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Vector3f;
import org.joml.Quaternionf;
import java.util.*;
import java.util.function.Consumer;

/** Owner-only, camera-relative roulette; GUI remains an optional fallback. */
public final class EventRoulette {
    private static final String TAG = "deruy_event_roulette";
    private static final float[] SIZES = {1f, 1.1f, 1.2f, 1.3f, 2f, 1.3f, 1.2f, 1f};
    private static final int CENTER = 4;
    private final DeruyPlugin plugin;
    private final List<RandomEventManager.Type> pool;
    private final RandomEventManager.Type winner;
    private final Consumer<RandomEventManager.Type> selected;
    private final Map<UUID, List<ItemDisplay>> displays = new HashMap<>();
    private final Map<UUID, TextDisplay> headings = new HashMap<>();
    private final Map<UUID, Gui> screens = new HashMap<>();
    private final Set<UUID> announced = new HashSet<>();
    private final int duration, stepTicks;
    private final boolean camera;
    private BukkitTask task;
    private int tick;

    public static final class Gui implements InventoryHolder {
        final Inventory inventory = Bukkit.createInventory(this, 54, "§6랜덤 이벤트 추첨");
        public Inventory getInventory() { return inventory; }
    }

    public EventRoulette(DeruyPlugin plugin, List<RandomEventManager.Type> pool,
                         Consumer<RandomEventManager.Type> selected) {
        if (pool.isEmpty()) throw new IllegalArgumentException("추첨 이벤트 목록이 비었습니다.");
        this.plugin = plugin;
        this.pool = List.copyOf(pool);
        this.selected = selected;
        stepTicks = plugin.getConfig().getInt("random-events.roulette-step-ticks", 2) == 4 ? 4 : 2;
        camera = !plugin.getConfig().getString("random-events.roulette-style", "CAMERA").equalsIgnoreCase("GUI");
        winner = pool.get(new Random().nextInt(pool.size()));
        duration = Math.clamp(plugin.getConfig().getInt("random-events.roulette-seconds", 5), 2, 30) * 20;
    }

    public void start() {
        // Standard titles cover the roulette center, so all headings use the top display.
        Bukkit.getOnlinePlayers().forEach(Player::resetTitle);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::frame, 1, 1);
    }

    /** Interpolate the requested top-to-bottom size profile as an item moves down. */
    static float sizeAt(double row) {
        int upper = Math.clamp((int) Math.floor(row), 0, SIZES.length - 1);
        int lower = Math.min(upper + 1, SIZES.length - 1);
        float fraction = (float) (row - Math.floor(row));
        return SIZES[upper] + (SIZES[lower] - SIZES[upper]) * fraction;
    }

 public static ItemStack icon(RandomEventManager.Type t){Material m=switch(t){case SHIELD_BAN,SWORD_SHIELD_BREAK->Material.SHIELD;case TOTEM_BAN->Material.TOTEM_OF_UNDYING;case POTION_BAN,POISON_HIT,STRENGTH_III,KILL_INVISIBILITY,PROJECTILE_WITHER->Material.POTION;case DOUBLE_DROPS->Material.DIAMOND;case MACE_BAN,MACE_DOUBLE_DAMAGE,MACE_SHOCKWAVE->Material.MACE;case KILL_DROP->Material.GOLDEN_APPLE;case CROP_REWARD->Material.WHEAT;case AXE_CRIT_SLOW->Material.DIAMOND_AXE;case TNT_DOUBLE_DAMAGE->Material.TNT;case PROJECTILE_BAN->Material.BOW;case KILL_VOUCHER->Material.CHEST;case COBWEB_BAN->Material.COBWEB;case LIFESTEAL->Material.REDSTONE;case COUNTERATTACK->Material.DIAMOND_SWORD;case POST_KILL_SPEED->Material.SUGAR;case FISHING_FRENZY->Material.FISHING_ROD;};ItemStack i=new ItemStack(m);if(i.getItemMeta() instanceof PotionMeta meta){meta.setBasePotionType(switch(t){case POISON_HIT->PotionType.POISON;case STRENGTH_III->PotionType.STRENGTH;case KILL_INVISIBILITY->PotionType.INVISIBILITY;case PROJECTILE_WITHER->PotionType.HARMING;default->PotionType.WATER;});if(t==RandomEventManager.Type.PROJECTILE_WITHER)meta.setColor(Color.BLACK);i.setItemMeta(meta);}return i;}

    private void frame() {
        try {
            tick++;
            Set<UUID> online = new HashSet<>();
            boolean finished = tick >= duration;
            for (Player p : Bukkit.getOnlinePlayers()) {
                UUID id = p.getUniqueId();
                online.add(id);
                List<ItemDisplay> list = displays.get(id);
                TextDisplay heading = headings.get(id);
                if ((list != null && list.stream().anyMatch(d -> !d.isValid() || !d.getWorld().equals(p.getWorld())))
                        || (heading != null && (!heading.isValid() || !heading.getWorld().equals(p.getWorld())))) {
                    removeViewer(id);
                    list = null;
                    heading = null;
                }
                if (heading == null) heading = createHeading(p);
                updateHeading(p, heading, finished);
                if (camera) {
                    if (list == null) list = createItems(p);
                    renderCamera(p, list, finished);
                } else {
                    renderGui(p, finished);
                }
                if (!finished && tick % stepTicks == 0)
                    p.playSound(p.getEyeLocation(), Sound.BLOCK_LEVER_CLICK, .35f, 1.5f);
                if (finished && announced.add(id))
                    p.playSound(p.getEyeLocation(), Sound.ENTITY_PLAYER_LEVELUP, .8f, 1.2f);
            }
            for (UUID id : new ArrayList<>(headings.keySet()))
                if (!online.contains(id)) removeViewer(id);
            if (tick >= duration + 30) {
                cancel();
                selected.accept(winner);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("추첨 표시 오류: " + e.getMessage());
            cancel();
            selected.accept(winner);
        }
    }

    private void setupDisplay(Display display) {
        display.setVisibleByDefault(false);
        display.setPersistent(false);
        display.setGravity(false);
        display.setInvulnerable(true);
        display.setBrightness(new Display.Brightness(15, 15));
        display.setTeleportDuration(1);
        display.setInterpolationDuration(1);
        display.setDisplayWidth(12);
        display.setDisplayHeight(12);
        display.addScoreboardTag(TAG);
    }

    private List<ItemDisplay> createItems(Player p) {
        List<ItemDisplay> list = new ArrayList<>();
        // Register before spawning, so partial failures still get cleaned up.
        displays.put(p.getUniqueId(), list);
        for (int j = 0; j < SIZES.length; j++) {
            ItemDisplay d = p.getWorld().spawn(p.getEyeLocation(), ItemDisplay.class, e -> {
                setupDisplay(e);
                e.setBillboard(Display.Billboard.CENTER);
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            });
            list.add(d);
            p.showEntity(plugin, d);
        }
        return list;
    }

    private TextDisplay createHeading(Player p) {
        TextDisplay d = p.getWorld().spawn(p.getEyeLocation(), TextDisplay.class, e -> {
            setupDisplay(e);
            e.setBillboard(Display.Billboard.CENTER);
            e.setAlignment(TextDisplay.TextAlignment.CENTER);
            e.setSeeThrough(true);
            e.setShadowed(true);
            e.setDefaultBackground(false);
            e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            e.setLineWidth(400);
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(.65f), new Quaternionf()));
        });
        headings.put(p.getUniqueId(), d);
        p.showEntity(plugin, d);
        return d;
    }

    private Location cameraLocation(Player p, double distance, double height) {
        Location eye = p.getEyeLocation();
        Vector forward = eye.getDirection().normalize();
        double yaw = Math.toRadians(eye.getYaw());
        Vector right = new Vector(Math.cos(yaw), 0, Math.sin(yaw));
        // Using yaw for right keeps this basis stable even looking straight up/down.
        Vector up = forward.clone().crossProduct(right).normalize();
        return eye.clone().add(forward.multiply(distance)).add(up.multiply(height));
    }

    private void updateHeading(Player p, TextDisplay heading, boolean finished) {
        String text = finished ? "§a선택된 이벤트\n" + eventName(winner)
                : "§6랜덤 이벤트 추첨\n§f위에서 아래로 아이템이 돌아갑니다";
        if (!text.equals(heading.getText())) heading.setText(text);
        heading.teleport(cameraLocation(p, 4.5, 2.95));
    }

    private void renderCamera(Player p, List<ItemDisplay> list, boolean finished) {
        double progress = tick / (double) stepTicks;
        for (int j = 0; j < list.size(); j++) {
            ItemDisplay d = list.get(j);
            if (finished && j != CENTER) {
                d.setItemStack(new ItemStack(Material.AIR));
                continue;
            }
            // Each entity moves downward, wrapping from the bottom to the top.
            double row = finished ? CENTER : (j + progress) % SIZES.length;
            int wraps = (int) Math.floor((j + progress) / SIZES.length);
            RandomEventManager.Type type = finished ? winner
                    : pool.get(Math.floorMod(j - wraps * SIZES.length, pool.size()));
            ItemStack item = icon(type);
            if (!d.getItemStack().equals(item)) d.setItemStack(item);
            double height = (CENTER - row) * .60;
            // Wrapped items snap back at the edge instead of sweeping upwards across the center.
            boolean wrapped = !finished && ((j + (tick - 1) / (double) stepTicks) % SIZES.length) > row;
            d.setTeleportDuration(wrapped || tick == duration ? 0 : 1);
            d.teleport(cameraLocation(p, 4.5, height));
            float scale = finished ? SIZES[CENTER] : sizeAt(row);
            d.setInterpolationDelay(0);
            d.setTransformation(new Transformation(new Vector3f(),
                    new Quaternionf().rotateY(tick * .12f), new Vector3f(scale), new Quaternionf()));
        }
    }

    private void renderGui(Player p, boolean finished) {
        Gui g = screens.get(p.getUniqueId());
        if (g == null) {
            g = new Gui();
            screens.put(p.getUniqueId(), g);
            p.openInventory(g.inventory);
        }
        for (int row = 0; row < 6; row++) {
            ItemStack item = finished ? (row == 2 ? namedIcon(winner) : null)
                    : namedIcon(pool.get(Math.floorMod(row - tick / stepTicks, pool.size())));
            g.inventory.setItem(row * 9 + 4, item);
        }
        ItemStack marker = new ItemStack(finished ? Material.LIME_STAINED_GLASS_PANE
                : Material.YELLOW_STAINED_GLASS_PANE);
        g.inventory.setItem(21, marker);
        g.inventory.setItem(23, marker);
    }

    private String eventName(RandomEventManager.Type type) {
        return ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("random-events.names." + type.name(), type.name()));
    }

    private ItemStack namedIcon(RandomEventManager.Type type) {
        ItemStack item = icon(type);
        item.editMeta(m -> m.setDisplayName(eventName(type)));
        return item;
    }

    private void removeViewer(UUID id) {
        List<ItemDisplay> list = displays.remove(id);
        if (list != null) list.forEach(Entity::remove);
        TextDisplay heading = headings.remove(id);
        if (heading != null) heading.remove();
        announced.remove(id);
        screens.remove(id);
    }

    public void cancel() {
        if (task != null) { task.cancel(); task = null; }
        for (Player p : Bukkit.getOnlinePlayers()) {
            Gui g = screens.get(p.getUniqueId());
            if (g != null && p.getOpenInventory().getTopInventory() == g.inventory) p.closeInventory();
        }
        for (UUID id : new ArrayList<>(headings.keySet())) removeViewer(id);
        // Includes any partly spawned viewer whose heading creation failed.
        displays.values().forEach(list -> list.forEach(Entity::remove));
        displays.clear();
        headings.clear();
        screens.clear();
        announced.clear();
    }
}
