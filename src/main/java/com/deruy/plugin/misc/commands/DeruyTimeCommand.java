package com.deruy.plugin.misc.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * /deruytime - 서버가 인식하는 현재 현실시간을 확인 (역할 이펙트 스케줄, 서플라이드랍 윈도우 디버깅용)
 */
public class DeruyTimeCommand implements CommandExecutor {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        sender.sendMessage("§b현재 서버 현실시간: §f" + LocalDateTime.now().format(FORMAT));
        return true;
    }
}
