package com.deruy.plugin;

import com.deruy.plugin.bingo.BingoListener;
import com.deruy.plugin.bingo.BingoBoardGuiListener;
import com.deruy.plugin.bingo.BingoManager;
import com.deruy.plugin.bingo.commands.BingoCommand;
import com.deruy.plugin.bounty.BountyManager;
import com.deruy.plugin.bounty.commands.BountyCommand;
import com.deruy.plugin.combatzone.CombatZoneListener;
import com.deruy.plugin.combatzone.CombatZoneVisualizer;
import com.deruy.plugin.data.DataStore;
import com.deruy.plugin.dragon.DragonListener;
import com.deruy.plugin.events.EventManager;
import com.deruy.plugin.events.commands.DeruyEventCommand;
import com.deruy.plugin.events.commands.KothCommand;
import com.deruy.plugin.events.koth.KothManager;
import com.deruy.plugin.lifesteal.LifeStealManager;
import com.deruy.plugin.lifesteal.RecipeManager;
import com.deruy.plugin.lifesteal.ItemLimitManager;
import com.deruy.plugin.lifesteal.CombatBossBarManager;
import com.deruy.plugin.lifesteal.commands.CombatCommand;
import com.deruy.plugin.lifesteal.commands.LifeStealCommand;
import com.deruy.plugin.lifesteal.commands.WithdrawCommand;
import com.deruy.plugin.lifesteal.commands.EnderEyesCommand;
import com.deruy.plugin.lifesteal.listeners.EnderEyeListener;
import com.deruy.plugin.lifesteal.listeners.DeathListener;
import com.deruy.plugin.lifesteal.listeners.EnchantRestrictionListener;
import com.deruy.plugin.lifesteal.listeners.EnderPearlListener;
import com.deruy.plugin.lifesteal.listeners.HeartDyeRestrictionListener;
import com.deruy.plugin.lifesteal.listeners.HeartItemListener;
import com.deruy.plugin.lifesteal.listeners.ItemLimitListener;
import com.deruy.plugin.lifesteal.listeners.LimitedItemDestructionListener;
import com.deruy.plugin.lifesteal.listeners.NetheriteRecipeBlockListener;
import com.deruy.plugin.lifesteal.listeners.RestrictionListener;
import com.deruy.plugin.lifesteal.listeners.TridentListener;
import com.deruy.plugin.locatorbar.LocatorBarManager;
import com.deruy.plugin.misc.MiscRuleListener;
import com.deruy.plugin.misc.commands.DeruyReloadCommand;
import com.deruy.plugin.misc.commands.DeruyTimeCommand;
import com.deruy.plugin.misc.commands.HornCommand;
import com.deruy.plugin.roleeffect.RoleEffectScheduler;
import com.deruy.plugin.role.RoleManager;
import com.deruy.plugin.role.RolePvpListener;
import com.deruy.plugin.role.commands.PvpCommand;
import com.deruy.plugin.voucher.VoucherManager;
import com.deruy.plugin.voucher.VoucherListener;
import com.deruy.plugin.voucher.commands.VoucherCommand;
import com.deruy.plugin.supplydrop.SupplyChestListener;
import com.deruy.plugin.supplydrop.SupplyChestRegistry;
import com.deruy.plugin.supplydrop.SupplyDropManager;
import com.deruy.plugin.supplydrop.SuperSupplyDropManager;
import com.deruy.plugin.supplydrop.commands.SupplyDropCommand;
import com.deruy.plugin.voicechat.DeruyVoicePitchPlugin;
import com.deruy.plugin.voicechat.InvisibilityVoiceEffectTracker;
import com.deruy.plugin.voicechat.KillLogObfuscationListener;
import com.deruy.plugin.voicechat.VoiceFeatureSettings;
import com.deruy.plugin.voicechat.commands.AutotuneCommand;
import com.deruy.plugin.voicechat.commands.KillLogCommand;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class DeruyPlugin extends JavaPlugin {

    private LifeStealManager lifeStealManager;
    private RecipeManager recipeManager;
    private ItemLimitManager itemLimitManager;
    private EventManager eventManager;
    private KothManager kothManager;
    private KothManager superKothManager;
    private LocatorBarManager locatorBarManager;
    private BingoManager bingoManager;
    private SupplyDropManager supplyDropManager;
    private SuperSupplyDropManager superSupplyDropManager;
    private BountyManager bountyManager;
    private RoleEffectScheduler roleEffectScheduler;
    private SupplyChestRegistry supplyChestRegistry;
    private CombatZoneVisualizer combatZoneVisualizer;
    private VoiceFeatureSettings voiceFeatureSettings;
    private InvisibilityVoiceEffectTracker invisibilityVoiceEffectTracker;
    private DeruyVoicePitchPlugin voicePitchPlugin;
    private RoleManager roleManager;
    private VoucherManager voucherManager;
    private DataStore dataStore;
    private CombatBossBarManager combatBossBarManager;

    private boolean worldGuardPresent;
    private boolean voiceChatPresent;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        worldGuardPresent = Bukkit.getPluginManager().getPlugin("WorldGuard") != null;

        // ---------------- 매니저 초기화 ----------------
        this.dataStore = new DataStore(this); // 다른 매니저 생성자가 참조하므로 제일 먼저
        this.lifeStealManager = new LifeStealManager(this);
        this.recipeManager = new RecipeManager(this);
        this.itemLimitManager = new ItemLimitManager(this);
        this.eventManager = new EventManager();
        this.kothManager = new KothManager(this);
        this.superKothManager = new KothManager(this, "superkoth", "SuperKOTH");
        this.locatorBarManager = new LocatorBarManager(this);
        this.bingoManager = new BingoManager(this);
        this.supplyDropManager = new SupplyDropManager(this);
        this.superSupplyDropManager = new SuperSupplyDropManager(this);
        this.bountyManager = new BountyManager(this);
        this.roleEffectScheduler = new RoleEffectScheduler(this);
        this.supplyChestRegistry = new SupplyChestRegistry(this);
        this.combatZoneVisualizer = new CombatZoneVisualizer(this);
        this.voiceFeatureSettings = new VoiceFeatureSettings(this);
        this.invisibilityVoiceEffectTracker = new InvisibilityVoiceEffectTracker();
        this.roleManager = new RoleManager(this);
        this.voucherManager = new VoucherManager(this);
        this.combatBossBarManager = new CombatBossBarManager(this);

        eventManager.register(kothManager);
        eventManager.register(superKothManager);
        eventManager.register(locatorBarManager);
        eventManager.register(bingoManager);
        eventManager.register(supplyDropManager);
        eventManager.register(superSupplyDropManager);
        eventManager.register(bountyManager);

        // 커스텀 레시피 등록 (#6, #7, #8, #11)
        recipeManager.registerAll();

        // ---------------- 리스너 등록 ----------------
        var pm = getServer().getPluginManager();
        pm.registerEvents(new DeathListener(this), this);
        pm.registerEvents(new TridentListener(this), this);            // #4
        pm.registerEvents(new RestrictionListener(this), this);        // #5
        pm.registerEvents(new ItemLimitListener(this), this);          // 제작개수 제한
        pm.registerEvents(new LimitedItemDestructionListener(this), this); // 제작개수 파괴추적
        pm.registerEvents(new EnchantRestrictionListener(this), this); // #10
        pm.registerEvents(new NetheriteRecipeBlockListener(this), this); // #13
        pm.registerEvents(new EnderPearlListener(this), this);          // 엔더진주 금지
        pm.registerEvents(new EnderEyeListener(this), this);            // 엔더의 눈 던지기 금지
        pm.registerEvents(new HeartItemListener(this), this);           // 하트 아이템 소비
        pm.registerEvents(new HeartDyeRestrictionListener(this), this); // 하트를 염료로 악용 방지
        pm.registerEvents(new DragonListener(this), this);             // 드래곤 체력/알
        pm.registerEvents(new MiscRuleListener(this), this);           // 토템/사망이펙트/화살
        pm.registerEvents(new BingoListener(this), this);
        pm.registerEvents(new BingoBoardGuiListener(), this);
        pm.registerEvents(new SupplyChestListener(this), this);        // 서플라이드랍 상자 즉시지급
        pm.registerEvents(invisibilityVoiceEffectTracker, this);       // 투명화 상태 추적 (오토튠 이펙트용)
        pm.registerEvents(new KillLogObfuscationListener(this), this); // 투명 상태 킬로그 노이즈 표시
        pm.registerEvents(new RolePvpListener(this), this);            // 역할별 PVP 허용여부
        pm.registerEvents(new VoucherListener(this), this);            // 상품권 개봉 강제

        if (worldGuardPresent) {
            pm.registerEvents(new CombatZoneListener(this), this);
            combatZoneVisualizer.start();
            getLogger().info("WorldGuard 감지됨: 컴벳존 차단 + 경계 시각화 기능 활성화.");
        } else {
            getLogger().warning("WorldGuard가 없어 컴벳존 차단 기능이 비활성화됩니다.");
        }

        // ---------------- SimpleVoiceChat 연동 (오토튠 이펙트) ----------------
        voiceChatPresent = Bukkit.getPluginManager().getPlugin("voicechat") != null;
        if (voiceChatPresent) {
            this.voicePitchPlugin = new DeruyVoicePitchPlugin(invisibilityVoiceEffectTracker, voiceFeatureSettings);
            invisibilityVoiceEffectTracker.setVoicePlugin(voicePitchPlugin);

            var provider = Bukkit.getServicesManager().getRegistration(BukkitVoicechatService.class);
            if (provider != null) {
                provider.getProvider().registerPlugin(voicePitchPlugin);
                getLogger().info("SimpleVoiceChat 감지됨: 투명화 오토튠 음성 이펙트 활성화.");
            } else {
                getLogger().warning("SimpleVoiceChat 서비스 등록을 찾을 수 없어 오토튠 이펙트가 비활성화됩니다.");
            }
        } else {
            getLogger().warning("SimpleVoiceChat이 없어 오토튠 음성 이펙트가 비활성화됩니다.");
        }

        // pending #1,#2,#3 (토템부활금지/엔더진주금지/XRay탐지) 은
        // 기존에 이런 제한 코드가 없으므로 별도 리스너 없이 "제한 없음" 상태 그대로 둠.
        // pending #9 (무제한 주민거래) 는 요청에 따라 구현하지 않음(삭제됨).

        // ---------------- 커맨드 등록 (실행기 + 탭완성) ----------------
        var lifeStealCmd = new LifeStealCommand(this);
        getCommand("lifesteal").setExecutor(lifeStealCmd);
        getCommand("lifesteal").setTabCompleter(lifeStealCmd);

        var withdrawCmd = new WithdrawCommand(this);
        getCommand("withdraw").setExecutor(withdrawCmd);
        getCommand("withdraw").setTabCompleter(withdrawCmd);

        var enderEyesCmd = new EnderEyesCommand(this);
        getCommand("endereyes").setExecutor(enderEyesCmd);
        getCommand("endereyes").setTabCompleter(enderEyesCmd);

        var eventCmd = new DeruyEventCommand(this);
        getCommand("devent").setExecutor(eventCmd);
        getCommand("devent").setTabCompleter(eventCmd);

        var kothCmd = new KothCommand(this, kothManager);
        getCommand("koth").setExecutor(kothCmd);
        getCommand("koth").setTabCompleter(kothCmd);

        var superKothCmd = new KothCommand(this, superKothManager);
        getCommand("superkoth").setExecutor(superKothCmd);
        getCommand("superkoth").setTabCompleter(superKothCmd);

        var bingoCmd = new BingoCommand(this);
        getCommand("bingo").setExecutor(bingoCmd);
        getCommand("bingo").setTabCompleter(bingoCmd);

        var bountyCmd = new BountyCommand(this);
        getCommand("bounty").setExecutor(bountyCmd);
        getCommand("bounty").setTabCompleter(bountyCmd);

        var supplyDropCmd = new SupplyDropCommand(this, false);
        getCommand("supplydrop").setExecutor(supplyDropCmd);
        getCommand("supplydrop").setTabCompleter(supplyDropCmd);

        var superSupplyDropCmd = new SupplyDropCommand(this, true);
        getCommand("supersupplydrop").setExecutor(superSupplyDropCmd);
        getCommand("supersupplydrop").setTabCompleter(superSupplyDropCmd);

        var combatCmd = new CombatCommand(this);
        getCommand("combat").setExecutor(combatCmd);
        getCommand("combat").setTabCompleter(combatCmd);

        getCommand("deruytime").setExecutor(new DeruyTimeCommand());
        getCommand("deruyreload").setExecutor(new DeruyReloadCommand(this));

        var autotuneCmd = new AutotuneCommand(this);
        getCommand("autotune").setExecutor(autotuneCmd);
        getCommand("autotune").setTabCompleter(autotuneCmd);

        var killLogCmd = new KillLogCommand(this);
        getCommand("killlog").setExecutor(killLogCmd);
        getCommand("killlog").setTabCompleter(killLogCmd);

        var pvpCmd = new PvpCommand(this);
        getCommand("pvp").setExecutor(pvpCmd);
        getCommand("pvp").setTabCompleter(pvpCmd);

        var voucherCmd = new VoucherCommand(this);
        getCommand("voucher").setExecutor(voucherCmd);
        getCommand("voucher").setTabCompleter(voucherCmd);

        var hornCmd = new HornCommand(this);
        getCommand("horn").setExecutor(hornCmd);
        getCommand("horn").setTabCompleter(hornCmd);

        // ---------------- 스케줄러 시작 ----------------
        roleEffectScheduler.start();
        combatBossBarManager.start();

        getLogger().info("DeruyPlugin 활성화 완료.");
    }

    @Override
    public void onDisable() {
        if (kothManager != null && kothManager.isRunning()) kothManager.stop();
        if (superKothManager != null && superKothManager.isRunning()) superKothManager.stop();
        if (locatorBarManager != null && locatorBarManager.isRunning()) locatorBarManager.stop();
        if (bingoManager != null && bingoManager.isRunning()) bingoManager.stop();
        if (supplyDropManager != null && supplyDropManager.isRunning()) supplyDropManager.stop();
        if (superSupplyDropManager != null && superSupplyDropManager.isRunning()) superSupplyDropManager.stop();
        if (bountyManager != null && bountyManager.isRunning()) bountyManager.stop();
        if (roleEffectScheduler != null) roleEffectScheduler.stop();
        if (combatZoneVisualizer != null) combatZoneVisualizer.stop();
        if (combatBossBarManager != null) combatBossBarManager.stop();
    }

    /**
     * LuckPerms 연동: 플레이어가 특정 role(그룹)에 속하는지 확인.
     * LuckPerms가 없으면 항상 false.
     */
    public boolean hasRole(Player player, String role) {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) return false;
        try {
            LuckPerms api = LuckPermsProvider.get();
            User user = api.getUserManager().getUser(player.getUniqueId());
            if (user == null) return false;

            // 권한노드(group.xxx) 체크는 OP인 플레이어에게 자동으로 true를 줘버리는 문제가 있어서
            // (bukkit.OpProcessor가 끼어듦), 순수 그룹 소속(상속 포함)만 직접 조회한다.
            for (net.luckperms.api.model.group.Group group : user.getInheritedGroups(user.getQueryOptions())) {
                if (group.getName().equalsIgnoreCase(role)) {
                    return true;
                }
            }
            return false;
        } catch (IllegalStateException e) {
            return false; // LuckPerms 아직 준비 안 됨
        }
    }

    public boolean isWorldGuardPresent() {
        return worldGuardPresent;
    }

    public boolean isVoiceChatPresent() {
        return voiceChatPresent;
    }

    public LifeStealManager getLifeStealManager() {
        return lifeStealManager;
    }

    public RecipeManager getRecipeManager() {
        return recipeManager;
    }

    public ItemLimitManager getItemLimitManager() {
        return itemLimitManager;
    }

    public EventManager getEventManager() {
        return eventManager;
    }

    public KothManager getKothManager() {
        return kothManager;
    }

    public KothManager getSuperKothManager() {
        return superKothManager;
    }

    public LocatorBarManager getLocatorBarManager() {
        return locatorBarManager;
    }

    public BingoManager getBingoManager() {
        return bingoManager;
    }

    public SupplyDropManager getSupplyDropManager() {
        return supplyDropManager;
    }

    public SuperSupplyDropManager getSuperSupplyDropManager() {
        return superSupplyDropManager;
    }

    public BountyManager getBountyManager() {
        return bountyManager;
    }

    public RoleEffectScheduler getRoleEffectScheduler() {
        return roleEffectScheduler;
    }

    public SupplyChestRegistry getSupplyChestRegistry() {
        return supplyChestRegistry;
    }

    public CombatZoneVisualizer getCombatZoneVisualizer() {
        return combatZoneVisualizer;
    }

    public VoiceFeatureSettings getVoiceFeatureSettings() {
        return voiceFeatureSettings;
    }

    public InvisibilityVoiceEffectTracker getInvisibilityVoiceEffectTracker() {
        return invisibilityVoiceEffectTracker;
    }

    public RoleManager getRoleManager() {
        return roleManager;
    }

    public VoucherManager getVoucherManager() {
        return voucherManager;
    }

    public DataStore getDataStore() {
        return dataStore;
    }

    public CombatBossBarManager getCombatBossBarManager() {
        return combatBossBarManager;
    }

    /**
     * config.yml의 messages.<key> 값을 읽어 '&' 색상코드를 변환해서 반환한다.
     * 설정이 없으면 defaultValue를 그대로(색상코드 변환 후) 사용한다.
     */
    public String getMessage(String key, String defaultValue) {
        String raw = getConfig().getString("messages." + key, defaultValue);
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', raw);
    }

    /**
     * config의 sounds.<key> 섹션(sound-name, pitch, volume, range)에 따라 사운드를 재생한다.
     * range: "0"=재생안함(무음), "00"=서버 전체(거리무관 전원), 그 외 숫자=그 블록반경 안의 플레이어에게만.
     */
    public void playConfiguredSound(String key, org.bukkit.Location loc) {
        var section = getConfig().getConfigurationSection("sounds." + key);
        if (section == null) return;

        String soundName = section.getString("sound-name");
        if (soundName == null) return;

        org.bukkit.Sound sound;
        try {
            sound = org.bukkit.Sound.valueOf(soundName.toUpperCase());
        } catch (IllegalArgumentException e) {
            getLogger().warning("sounds." + key + ".sound-name 값이 잘못됨: " + soundName);
            return;
        }

        float volume = (float) section.getDouble("volume", 1.0);
        float pitch = (float) section.getDouble("pitch", 1.0);
        String rangeRaw = section.getString("range", "20").trim();

        if (rangeRaw.equals("0")) {
            return; // 무음
        }

        if (rangeRaw.equals("00")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.playSound(loc, sound, volume, pitch);
            }
            return;
        }

        int range;
        try {
            range = Integer.parseInt(rangeRaw);
        } catch (NumberFormatException e) {
            getLogger().warning("sounds." + key + ".range 값이 잘못됨: " + rangeRaw + " (숫자, \"0\", \"00\" 중 하나여야 함)");
            range = 20;
        }

        for (Player p : loc.getWorld().getPlayers()) {
            if (p.getLocation().distance(loc) <= range) {
                p.playSound(loc, sound, volume, pitch);
            }
        }
    }

    /**
     * 바운티/빙고/KOTH 등 목표달성시 그 위치에서 무지개색 폭죽을 즉시 터뜨린다.
     * (날아가지 않고 그 자리에서 바로 터짐)
     */
    public void spawnRainbowFirework(org.bukkit.Location loc) {
        var firework = loc.getWorld().spawn(loc, org.bukkit.entity.Firework.class);
        var meta = firework.getFireworkMeta();

        var effect = org.bukkit.FireworkEffect.builder()
                .withColor(
                        org.bukkit.Color.RED, org.bukkit.Color.ORANGE, org.bukkit.Color.YELLOW,
                        org.bukkit.Color.LIME, org.bukkit.Color.AQUA, org.bukkit.Color.BLUE,
                        org.bukkit.Color.PURPLE
                )
                .withFade(org.bukkit.Color.WHITE)
                .with(org.bukkit.FireworkEffect.Type.BALL_LARGE)
                .trail(true)
                .flicker(true)
                .build();

        meta.addEffect(effect);
        meta.setPower(1);
        firework.setFireworkMeta(meta);
        firework.detonate();
    }
}
