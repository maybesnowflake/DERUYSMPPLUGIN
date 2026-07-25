package com.deruy.plugin.misc.commands;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/**
 * /deruyreload - config.yml을 디스크에서 다시 읽어들인다 (서버 재시작 불필요).
 * 대부분의 값(min/max하트, 메시지, 확률, 시간대 등)은 캐싱 없이 매번 config를 직접 읽으므로
 * 이 커맨드 한 번이면 config.yml 수정사항이 즉시 반영된다.
 * (단, 레시피 모양/재료는 서버가 켜질 때만 등록되므로 레시피 변경은 재시작이 필요함)
 */
public class DeruyReloadCommand implements CommandExecutor {

    private final DeruyPlugin plugin;

    public DeruyReloadCommand(DeruyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        plugin.reloadConfig();
        sender.sendMessage("§aconfig.yml을 다시 불러왔습니다. (레시피 변경은 서버 재시작이 필요합니다)");
        return true;
    }
}
