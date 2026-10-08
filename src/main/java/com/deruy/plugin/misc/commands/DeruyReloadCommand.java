package com.deruy.plugin.misc.commands;
import com.deruy.plugin.DeruyPlugin;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class DeruyReloadCommand implements CommandExecutor {
    private final DeruyPlugin plugin;
    public DeruyReloadCommand(DeruyPlugin plugin) { this.plugin = plugin; }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        try {
            var candidate=new YamlConfiguration();
            candidate.load(new File(plugin.getDataFolder(),"config.yml"));
            try(var stream=plugin.getResource("config.yml")) {
                if(stream!=null) candidate.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(stream,StandardCharsets.UTF_8)));
            }
            com.deruy.plugin.config.ConfigValidator.validate(candidate);
        } catch(Exception e) {
            sender.sendMessage("§c설정 오류로 리로드를 취소했습니다. 기존 설정을 유지합니다: " + e.getMessage()); return true;
        }
        var oldConfig=plugin.getConfig();
        int oldSize=oldConfig.getInt("bingo.size");
        var oldItems=oldConfig.getStringList("bingo.excluded-items");
        var oldBiomes=oldConfig.getStringList("bingo.excluded-biomes");
        boolean bingoRunning=plugin.getBingoManager().isRunning();
        plugin.reloadConfig();
        boolean rebuildBingo=bingoRunning && (oldSize!=plugin.getConfig().getInt("bingo.size")
            || !oldItems.equals(plugin.getConfig().getStringList("bingo.excluded-items"))
            || !oldBiomes.equals(plugin.getConfig().getStringList("bingo.excluded-biomes")));
        boolean locatorEnabled=plugin.getConfig().getBoolean("locatorbar.enabled",false);
        plugin.getLifeStealManager().reloadSettings();
        plugin.getVoiceFeatureSettings().reloadSettings();
        plugin.getKothManager().reloadSettings(); plugin.getSuperKothManager().reloadSettings();
        plugin.getRecipeManager().registerAll();
        plugin.getCombatZoneVisualizer().stop();
        if(plugin.isWorldGuardPresent()) plugin.getCombatZoneVisualizer().start();
        plugin.getLocatorBarManager().stop();
        if(locatorEnabled) plugin.getLocatorBarManager().start();
        plugin.getSupplyDropManager().stop(); plugin.getSuperSupplyDropManager().stop();
        if(plugin.getConfig().getBoolean("supplydrop.normal.enabled",false)) plugin.getSupplyDropManager().start();
        if(plugin.getConfig().getBoolean("supplydrop.super.enabled",false)) plugin.getSuperSupplyDropManager().start();
        plugin.getQuestionTimeManager().stop();
        if(plugin.getConfig().getBoolean("question-time.enabled",false)) plugin.getQuestionTimeManager().start();
        plugin.getRandomEventManager().stopScheduler();
        if(plugin.getConfig().getBoolean("random-events.enabled",false)) plugin.getRandomEventManager().startScheduler();
        plugin.getBingoManager().reloadMilestones();
        if(rebuildBingo) { plugin.getBingoManager().stop(); plugin.getBingoManager().start(); }
        sender.sendMessage("§a설정·조합법·기능 상태를 다시 적용했습니다. 보급 생성 예약은 취소하고 일정을 다시 계산했습니다.");
        if(rebuildBingo) sender.sendMessage("§e빙고 설정 적용으로 진행 중이던 빙고판이 새로 생성되었습니다.");
        return true;
    }
}
