package com.deruy.plugin.randomevent;

import java.util.*;
import java.util.function.ToDoubleFunction;

/** 룰렛 계산(Bukkit 없음): 가중치 추첨, 감속 곡선, 당첨 칸에 정확히 멈추는 아이템 줄. */
final class RouletteMath {
    private RouletteMath() {}

    /** 가중치 비례 추첨. 가중치가 전부 0 이하이면 균등 추첨. r은 [0,1). */
    static <T> T weightedPick(List<T> pool, ToDoubleFunction<T> weight, double r) {
        if (pool.isEmpty()) throw new IllegalArgumentException("추첨 목록이 비었습니다.");
        double total = 0;
        for (T t : pool) total += Math.max(0, weight.applyAsDouble(t));
        if (!(total > 0)) return pool.get(Math.min(pool.size() - 1, (int) (r * pool.size())));
        double x = r * total;
        for (T t : pool) {
            double w = Math.max(0, weight.applyAsDouble(t));
            if (x < w) return t;
            x -= w;
        }
        for (int i = pool.size() - 1; i >= 0; i--) if (weight.applyAsDouble(pool.get(i)) > 0) return pool.get(i);
        return pool.get(pool.size() - 1);
    }

    /** 감속(ease-out cubic) 위치. tick=0 → 0, tick>=spinTicks → totalSteps 정확히. */
    static double position(int tick, int spinTicks, int totalSteps) {
        if (tick <= 0) return 0;
        if (tick >= spinTicks) return totalSteps;
        double t = tick / (double) spinTicks;
        double left = 1 - t;
        return totalSteps * (1 - left * left * left);
    }

    /** 가장 빠를 때 한 칸에 stepTicks틱이 되도록 전체 칸 수를 정한다. */
    static int totalSteps(int spinTicks, int stepTicks, int minimum) {
        // ease-out cubic 시작 속도 = 3 * total / spin (칸/틱) → total = spin / (3 * step)
        return Math.max(minimum, (int) Math.round(spinTicks / (3.0 * stepTicks)));
    }

    /**
     * index -pad .. totalSteps+pad 의 아이템 줄. strip.get(i + pad) 가 index i.
     * index totalSteps(멈추는 칸)는 winner, 바로 앞뒤 칸은 winner가 아니게(아슬아슬 연출).
     */
    static <T> List<T> strip(List<T> pool, T winner, int totalSteps, int pad, Random random) {
        if (pool.isEmpty()) throw new IllegalArgumentException("추첨 목록이 비었습니다.");
        List<T> order = new ArrayList<>(pool);
        Collections.shuffle(order, random);
        int size = totalSteps + 2 * pad + 1;
        List<T> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) out.add(order.get(i % order.size()));
        int stop = totalSteps + pad;
        out.set(stop, winner);
        if (pool.size() > 1) {
            for (int n : new int[]{stop - 1, stop + 1}) {
                if (n < 0 || n >= size || !Objects.equals(out.get(n), winner)) continue;
                for (T t : order) if (!Objects.equals(t, winner)) { out.set(n, t); break; }
            }
        }
        return out;
    }
}
