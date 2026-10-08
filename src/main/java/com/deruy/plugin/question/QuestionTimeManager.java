package com.deruy.plugin.question;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Config 질문과 RANDOM 수학 문제를 채팅으로 푸는 랜덤 이벤트. */
public final class QuestionTimeManager implements Listener {
    private final DeruyPlugin plugin;
    private BukkitTask nextTask, timeoutTask;
    private boolean enabled;
    private Set<String> answers = Set.of();
    private boolean accepting;

    public QuestionTimeManager(DeruyPlugin plugin) { this.plugin = plugin; }

    public void start() { enabled = true; scheduleNext(); }
    public void stop() { enabled = false; accepting = false; cancel(nextTask); cancel(timeoutTask); }
    public boolean isEnabled() { return enabled; }
    public boolean isQuestionActive() { return accepting; }

    private void cancel(BukkitTask task) { if (task != null) task.cancel(); }
    private void scheduleNext() {
        cancel(nextTask);
        if (!enabled) return;
        long min = Math.max(1, plugin.getConfig().getLong("question-time.interval-seconds.min", 600));
        long max = Math.max(min, plugin.getConfig().getLong("question-time.interval-seconds.max", 1800));
        long delay = ThreadLocalRandom.current().nextLong(min, max + 1) * 20L;
        nextTask = Bukkit.getScheduler().runTaskLater(plugin, this::askRandom, delay);
    }

    public boolean askRandom() {
        if (accepting) return false;
        List<Map<?, ?>> list = plugin.getConfig().getMapList("question-time.questions");
        if (list.isEmpty()) return false;
        Map<?, ?> entry = list.get(ThreadLocalRandom.current().nextInt(list.size()));
        String q = Objects.toString(entry.get("Q"), "").trim();
        if (q.equalsIgnoreCase("RANDOM")) askMath(entry);
        else {
            Object raw = entry.get("A");
            List<String> valid = raw instanceof List<?> values ? values.stream().map(Object::toString).toList()
                    : Arrays.stream(Objects.toString(raw, "").split(",")).toList();
            open(q, valid);
        }
        return true;
    }

    private void askMath(Map<?, ?> entry) {
        ConfigurationSection math = plugin.getConfig().getConfigurationSection("question-time.random-math");
        int min = math == null ? 1 : math.getInt("min-number", 1);
        int max = math == null ? 100 : Math.max(min, math.getInt("max-number", 100));
        List<String> ops = math == null ? List.of("+", "-", "*") : math.getStringList("operations");
        if (ops.isEmpty()) ops = List.of("+");
        int a = ThreadLocalRandom.current().nextInt(min, max + 1);
        int b = ThreadLocalRandom.current().nextInt(min, max + 1);
        String op = ops.get(ThreadLocalRandom.current().nextInt(ops.size()));
        if (op.equals("-") && b > a) { int t = a; a = b; b = t; }
        int result = switch (op) { case "-" -> a-b; case "*", "x", "×" -> a*b; default -> a+b; };
        open(a + " " + op + " " + b + " = ?", List.of(Integer.toString(result)));
    }

    private void open(String question, List<String> valid) {
        answers = valid.stream().map(this::normalize).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (question.isEmpty() || answers.isEmpty()) { scheduleNext(); return; }
        accepting = true;
        Bukkit.broadcastMessage("§6§l[질문타임] §f" + question);
        Bukkit.broadcastMessage("§7제한 시간 안에 정답을 채팅으로 입력하세요!");
        long seconds = Math.max(1, plugin.getConfig().getLong("question-time.answer-time-seconds", 30));
        timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (accepting) Bukkit.broadcastMessage("§6[질문타임] §c시간이 종료되었습니다.");
            accepting = false; answers = Set.of(); scheduleNext();
        }, seconds * 20L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!accepting || !answers.contains(normalize(event.getMessage()))) return;
        accepting = false; cancel(timeoutTask);
        Player winner = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Bukkit.broadcastMessage("§6§l[질문타임] §a" + winner.getName() + "님이 정답을 맞혔습니다!");
            var rewards = plugin.getSupplyChestRegistry().rollRewards(plugin.getConfig().getStringList("question-time.reward-items"), plugin.getLogger());
            rewards.forEach(item -> winner.getInventory().addItem(item).values().forEach(left -> winner.getWorld().dropItemNaturally(winner.getLocation(), left)));
            scheduleNext();
        });
    }

    private String normalize(String s) { return s == null ? "" : s.strip().replaceAll("\\s+", "").toLowerCase(Locale.ROOT); }
}
