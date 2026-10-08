package com.deruy.plugin.lifesteal;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.entity.Player;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Ordered writes off the server thread; daily UTF-8 TSV audit files. */
public final class HeartAuditLog implements AutoCloseable {
    private final DeruyPlugin plugin;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Deruy-HeartAudit"); t.setDaemon(true); return t;
    });
    public HeartAuditLog(DeruyPlugin plugin) { this.plugin = plugin; }
    private static String clean(String s) { return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '); }
    public void record(Player p, double before, double after, String reason) {
        record(p.getUniqueId(), p.getName(), before, after, reason);
    }
    public void record(UUID id, String name, double before, double after, String reason) {
        if (!plugin.getConfig().getBoolean("heart.audit-enabled", true)) return;
        String now = Instant.now().toString();
        String line = now + "\t" + id + "\t" + clean(name) + "\t" + before + "\t" + after + "\t" + clean(reason) + "\n";
        var path = plugin.getDataFolder().toPath().resolve("logs/hearts-" + now.substring(0,10) + ".tsv");
        writer.execute(() -> {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) { plugin.getLogger().severe("하트 기록 저장 실패: " + e.getMessage()); }
        });
    }
    @Override public void close() {
        writer.shutdown();
        try { if (!writer.awaitTermination(5, TimeUnit.SECONDS)) plugin.getLogger().warning("하트 로그 저장이 지연되고 있습니다."); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
