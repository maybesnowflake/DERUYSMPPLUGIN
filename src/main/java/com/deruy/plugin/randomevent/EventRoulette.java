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
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;
import java.util.function.Consumer;

/**
 * 랜덤 이벤트 추첨 룰렛. 모든 접속자에게 각자 시야 앞에 세로 릴을 보여 준다(본인에게만 보이는 디스플레이).
 * 당첨은 시작 전에 정해지고, 릴은 감속하며 당첨 칸에서 정확히 멈춘다(마지막에 바꿔치기 없음).
 * roulette-style: GUI 이면 상자 화면으로 같은 릴을 보여 준다.
 */
public final class EventRoulette {
    private static final String TAG = "deruy_event_roulette";
    /** 위(0) → 아래(7) 크기. 가운데(4)가 당첨선. */
    private static final float[] SIZES = {1f, 1.1f, 1.2f, 1.3f, 2f, 1.3f, 1.2f, 1f};
    private static final int SLOTS = SIZES.length;
    private static final int CENTER = 4;
    private static final int PAD = SLOTS;
    private static final int REVEAL_TICKS = 40;
    private static final double SPACING = .60, DISTANCE = 4.5;

    private final DeruyPlugin plugin;
    private final RandomEventManager.Type winner;
    private final Consumer<RandomEventManager.Type> selected;
    private final List<RandomEventManager.Type> strip;
    private final int spinTicks, totalSteps;
    private final boolean camera;
    private final Map<UUID, Viewer> viewers = new HashMap<>();
    private BukkitTask task;
    private int tick, lastCenter = Integer.MIN_VALUE;
    private boolean done;

    public static final class Gui implements InventoryHolder {
        final Inventory inventory = Bukkit.createInventory(this, 54, "§6랜덤 이벤트 추첨");
        public Inventory getInventory() { return inventory; }
    }

    /** 한 플레이어에게 보이는 엔티티들. */
    private static final class Viewer {
        final List<ItemDisplay> items = new ArrayList<>();
        final int[] shown = new int[SLOTS];
        TextDisplay heading;
        Gui gui;
        boolean revealed;
        Viewer() { Arrays.fill(shown, Integer.MIN_VALUE); }
    }

    public EventRoulette(DeruyPlugin plugin, List<RandomEventManager.Type> pool, RandomEventManager.Type winner,
                         Consumer<RandomEventManager.Type> selected) {
        if (pool.isEmpty()) throw new IllegalArgumentException("추첨 이벤트 목록이 비었습니다.");
        if (!pool.contains(winner)) throw new IllegalArgumentException("당첨 이벤트가 목록에 없습니다: " + winner);
        this.plugin = plugin;
        this.winner = winner;
        this.selected = selected;
        int stepTicks = plugin.getConfig().getInt("random-events.roulette-step-ticks", 2) == 4 ? 4 : 2;
        camera = !plugin.getConfig().getString("random-events.roulette-style", "CAMERA").equalsIgnoreCase("GUI");
        spinTicks = Math.clamp(plugin.getConfig().getInt("random-events.roulette-seconds", 5), 2, 30) * 20;
        totalSteps = RouletteMath.totalSteps(spinTicks, stepTicks, SLOTS * 2);
        strip = RouletteMath.strip(pool, winner, totalSteps, PAD, new Random());
    }

    public void start() {
        // 기본 타이틀이 릴 가운데를 가리므로 지운다. 제목은 위쪽 텍스트 디스플레이로.
        com.deruy.plugin.misc.Online.players().forEach(Player::resetTitle);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::frame, 1, 1);
    }

    /** 위→아래 크기 곡선 보간. row 0~7, 범위 밖은 끝값. */
    static float sizeAt(double row) {
        if (row <= 0) return SIZES[0];
        if (row >= SLOTS - 1) return SIZES[SLOTS - 1];
        int upper = (int) Math.floor(row);
        float fraction = (float) (row - upper);
        return SIZES[upper] + (SIZES[upper + 1] - SIZES[upper]) * fraction;
    }

    private RandomEventManager.Type at(int index) {
        int i = index + PAD;
        return strip.get(Math.clamp(i, 0, strip.size() - 1));
    }

    public static ItemStack icon(RandomEventManager.Type t) {
        Material m = switch (t) {
            case SHIELD_BAN,SWORD_SHIELD_BREAK->Material.SHIELD;
            case TOTEM_BAN->Material.TOTEM_OF_UNDYING;
            case POTION_BAN,POISON_HIT,STRENGTH_III,KILL_INVISIBILITY,PROJECTILE_WITHER->Material.POTION;
            case DOUBLE_DROPS->Material.DIAMOND;
            case MACE_BAN,MACE_DOUBLE_DAMAGE,MACE_SHOCKWAVE->Material.MACE;
            case KILL_DROP->Material.GOLDEN_APPLE;
            case CROP_REWARD->Material.WHEAT;
            case AXE_CRIT_SLOW->Material.DIAMOND_AXE;
            case TNT_DOUBLE_DAMAGE->Material.TNT;
            case PROJECTILE_BAN->Material.BOW;
            case KILL_VOUCHER->Material.CHEST;
            case COBWEB_BAN->Material.COBWEB;
            case LIFESTEAL->Material.REDSTONE;
            case COUNTERATTACK->Material.DIAMOND_SWORD;
            case POST_KILL_SPEED->Material.SUGAR;
            case FISHING_FRENZY->Material.FISHING_ROD;
        };
        ItemStack i = new ItemStack(m);
        if (i.getItemMeta() instanceof PotionMeta meta){meta.setBasePotionType(switch(t){case POISON_HIT->PotionType.POISON;
            case STRENGTH_III->PotionType.STRENGTH;
            case KILL_INVISIBILITY->PotionType.INVISIBILITY;
            case PROJECTILE_WITHER->PotionType.HARMING;default->PotionType.WATER;});if(t==RandomEventManager.Type.PROJECTILE_WITHER)meta.setColor(Color.BLACK);i.setItemMeta(meta);}
        return i;
    }

    private void frame() {
        if (done) return;
        try {
            tick++;
            boolean revealing = tick > spinTicks;
            double p = RouletteMath.position(tick, spinTicks, totalSteps);
            int center = (int) Math.floor(p + .5);
            boolean passed = !revealing && center != lastCenter && lastCenter != Integer.MIN_VALUE;
            lastCenter = center;
            float pitch = (float) (0.9 + 0.8 * Math.min(1, tick / (double) spinTicks));

            Set<UUID> online = new HashSet<>();
            for (Player p0 : com.deruy.plugin.misc.Online.players()) {
                online.add(p0.getUniqueId());
                Viewer v = viewer(p0);
                updateHeading(p0, v, revealing ? winner : at(center), revealing);
                if (camera) renderCamera(p0, v, p, revealing);
                else renderGui(p0, v, p, revealing);
                if (passed) p0.playSound(p0.getEyeLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, .5f, pitch);
                if (revealing && !v.revealed) {
                    v.revealed = true;
                    p0.playSound(p0.getEyeLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, .7f, 1f);
                    if (camera) p0.spawnParticle(Particle.TOTEM_OF_UNDYING, cameraLocation(p0, DISTANCE, 0), 40, .4, .4, .4, .25);
                }
            }
            for (UUID id : new ArrayList<>(viewers.keySet())) if (!online.contains(id)) removeViewer(id);
            if (tick >= spinTicks + REVEAL_TICKS) finish();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("추첨 표시 오류: " + e.getMessage());
            finish();
        }
    }

    private void finish() {
        if (done) return;
        cancel();
        done = true;
        selected.accept(winner);
    }

    /** 플레이어용 엔티티를 준비. 다른 월드로 가거나 엔티티가 사라졌으면 새로 만든다. */
    private Viewer viewer(Player p) {
        Viewer v = viewers.get(p.getUniqueId());
        if (v != null && camera && (v.heading == null || !v.heading.isValid() || !v.heading.getWorld().equals(p.getWorld())
                || v.items.stream().anyMatch(d -> !d.isValid() || !d.getWorld().equals(p.getWorld())))) {
            boolean revealed = v.revealed;
            removeViewer(p.getUniqueId());
            v = null;
            if (revealed) { v = create(p); v.revealed = true; }
        }
        return v != null ? v : create(p);
    }

    private Viewer create(Player p) {
        Viewer v = new Viewer();
        // 먼저 등록해야 생성 도중 실패해도 정리된다.
        viewers.put(p.getUniqueId(), v);
        if (!camera) return v; // 상자 화면이 시야를 덮으므로 GUI 모드는 제목 디스플레이 없이 진행
        v.heading = p.getWorld().spawn(p.getEyeLocation(), TextDisplay.class, e -> {
            setupDisplay(e);
            e.setBillboard(Display.Billboard.CENTER);
            e.setAlignment(TextDisplay.TextAlignment.CENTER);
            e.setSeeThrough(true);
            e.setShadowed(true);
            e.setDefaultBackground(false);
            e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            e.setLineWidth(400);
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(.65f), new Quaternionf()));
        });
        p.showEntity(plugin, v.heading);
        for (int j = 0; j < SLOTS; j++) {
            ItemDisplay d = p.getWorld().spawn(p.getEyeLocation(), ItemDisplay.class, e -> {
                setupDisplay(e);
                e.setBillboard(Display.Billboard.CENTER);
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            });
            v.items.add(d);
            p.showEntity(plugin, d);
        }
        return v;
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

    private Location cameraLocation(Player p, double distance, double height) {
        Location eye = p.getEyeLocation();
        Vector forward = eye.getDirection().normalize();
        double yaw = Math.toRadians(eye.getYaw());
        // yaw로 오른쪽 축을 잡아 위/아래를 똑바로 봐도 축이 흔들리지 않는다.
        Vector right = new Vector(Math.cos(yaw), 0, Math.sin(yaw));
        Vector up = forward.clone().crossProduct(right).normalize();
        return eye.clone().add(forward.multiply(distance)).add(up.multiply(height));
    }

    private void updateHeading(Player p, Viewer v, RandomEventManager.Type shown, boolean revealing) {
        if (v.heading == null) return;
        String text = revealing ? "§a§l▶ 선택된 이벤트 ◀\n§e§l" + eventName(winner)
                : "§6§l랜덤 이벤트 추첨\n§f" + eventName(shown);
        if (!text.equals(v.heading.getText())) v.heading.setText(text);
        v.heading.teleport(cameraLocation(p, DISTANCE, 2.95));
    }

    /**
     * 아이템 index k 는 스크롤 위치 p 일 때 row = CENTER + (p - k) 에 있다(위에서 내려옴).
     * 보이는 범위 row 0~7 → k ∈ [floor(p)-3, floor(p)+4]. 디스플레이 j 는 그 중 k ≡ j (mod 8) 을 맡는다.
     */
    private void renderCamera(Player p, Viewer v, double pos, boolean revealing) {
        int base = (int) Math.floor(pos) - (SLOTS - 1 - CENTER);
        for (int j = 0; j < v.items.size(); j++) {
            ItemDisplay d = v.items.get(j);
            int k = revealing ? totalSteps : base + Math.floorMod(j - base, SLOTS);
            if (revealing && Math.floorMod(totalSteps, SLOTS) != j) {
                if (!d.getItemStack().isEmpty()) d.setItemStack(new ItemStack(Material.AIR));
                continue;
            }
            ItemStack item = icon(at(k));
            if (!d.getItemStack().equals(item)) d.setItemStack(item);
            double row = CENTER + (pos - k);
            // 맨 아래에서 맨 위로 돌아가는 순간은 순간이동(중앙을 가로질러 올라가 보이지 않게).
            boolean wrapped = v.shown[j] != k;
            v.shown[j] = k;
            d.setTeleportDuration(wrapped ? 0 : 1);
            d.teleport(cameraLocation(p, DISTANCE, (CENTER - row) * SPACING));
            float scale;
            float spin;
            if (revealing) {
                int r = tick - spinTicks;
                scale = SIZES[CENTER] + (float) (.35 * Math.sin(Math.min(r, 10) / 10.0 * Math.PI)) + (r > 10 ? .1f : 0f);
                spin = r * .25f;
            } else {
                scale = sizeAt(row);
                spin = tick * .12f;
            }
            d.setInterpolationDelay(0);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateY(spin),
                    new Vector3f(scale), new Quaternionf()));
        }
    }

    /** 상자 화면: 가운데 열, 3번째 줄(slot 22)이 당첨선. 위 줄이 다음에 내려올 아이템. */
    private void renderGui(Player p, Viewer v, double pos, boolean revealing) {
        if (v.gui == null) {
            v.gui = new Gui();
            p.openInventory(v.gui.inventory);
        }
        int center = (int) Math.floor(pos + .5);
        for (int row = 0; row < 6; row++) {
            ItemStack item = revealing ? (row == 2 ? namedIcon(winner) : null) : namedIcon(at(center + (2 - row)));
            v.gui.inventory.setItem(row * 9 + 4, item);
        }
        ItemStack marker = new ItemStack(revealing ? Material.LIME_STAINED_GLASS_PANE : Material.YELLOW_STAINED_GLASS_PANE);
        v.gui.inventory.setItem(21, marker);
        v.gui.inventory.setItem(23, marker);
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
        Viewer v = viewers.remove(id);
        if (v == null) return;
        v.items.forEach(Entity::remove);
        if (v.heading != null) v.heading.remove();
        if (v.gui != null) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.getOpenInventory().getTopInventory() == v.gui.inventory) p.closeInventory();
        }
    }

    public void cancel() {
        done = true;
        if (task != null) { task.cancel(); task = null; }
        for (UUID id : new ArrayList<>(viewers.keySet())) removeViewer(id);
        viewers.clear();
    }

}
