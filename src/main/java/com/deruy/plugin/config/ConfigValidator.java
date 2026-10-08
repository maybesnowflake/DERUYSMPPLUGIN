package com.deruy.plugin.config;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.Material;
import java.time.LocalTime;
import java.util.*;

/** Validate before any runtime state is replaced. */
public final class ConfigValidator {
    private ConfigValidator() {}
    private static void fail(String key, String detail) { throw new IllegalArgumentException(key + ": " + detail); }
    private static double number(ConfigurationSection c, String k, double min, double max) {
        Object raw = c.get(k);
        if (!(raw instanceof Number)) { fail(k, "숫자가 필요합니다"); return 0; }
        double n = ((Number)raw).doubleValue();
        if (!Double.isFinite(n) || n < min || n > max) fail(k, min + "~" + max + " 범위여야 합니다");
        return n;
    }
    private static int integer(ConfigurationSection c, String k, int min, int max) {
        double n = number(c,k,min,max); if (n != Math.rint(n)) fail(k,"정수가 필요합니다"); return (int)n;
    }
    private static void optionalInt(ConfigurationSection c,String k,int min,int max) { if(c.contains(k)) integer(c,k,min,max); }
    private static void choice(ConfigurationSection c, String k, String fallback, String... options) {
        String v = c.getString(k, fallback);
        if (Arrays.stream(options).noneMatch(s -> s.equalsIgnoreCase(v))) fail(k, String.join(" / ",options));
    }
    public static void validate(ConfigurationSection c) {
        // All known boolean switches must actually be YAML booleans.
        for (String k : c.getKeys(true)) {
            if (k.startsWith("messages.")) continue;
            if (k.endsWith(".enabled") || k.endsWith("-enabled") || k.endsWith("-blocked")
                    || k.endsWith(".pvp-allowed") || k.equals("heart.audit-enabled")) {
                if (!(c.get(k) instanceof Boolean)) fail(k,"true 또는 false가 필요합니다");
            }
        }
        int min = integer(c,"lifesteal.heart-limits.min-hearts",1,1023);
        int max = integer(c,"lifesteal.heart-limits.max-hearts",min+1,1024);
        var roles = c.getConfigurationSection("roles");
        if (roles != null) for (String role : roles.getKeys(false)) {
            if (roles.contains(role+".max-hearts")) integer(c,"roles."+role+".max-hearts",min+1,1024);
        }
        optionalInt(c,"heart.revive-hearts",min+1,max);
        integer(c,"combat-tag.duration-seconds",1,86400);
        choice(c,"combat-tag.logout-penalty","CHAT","CHAT","KILL","NONE");
        choice(c,"supplydrop.chest-destruction","PROTECT","PROTECT","DROP");
        number(c,"voice-effects.pitch-semitones",-12,12);
        integer(c,"bingo.size",1,6);
        int size = c.getInt("bingo.size");
        for (Object v : c.getList("bingo.line-milestones",List.of(1))) {
            if (!(v instanceof Number n) || n.doubleValue()!=n.intValue() || n.intValue()<1 || n.intValue()>size*2+2)
                fail("bingo.line-milestones","1~"+(size*2+2)+" 사이 정수");
        }
        integer(c,"locatorbar.update-interval-ticks",1,1200);
        number(c,"dragon.health",1,2048*1024);
        integer(c,"dragon.egg.acquire-hold-seconds",1,86400);
        number(c,"death.drop-scatter-radius",0,100);
        integer(c,"combat-zone.visualize-radius",1,32);
        integer(c,"question-time.interval-seconds.min",1,31536000);
        int qMin=c.getInt("question-time.interval-seconds.min");
        integer(c,"question-time.interval-seconds.max",qMin,31536000);
        integer(c,"question-time.answer-time-seconds",1,86400);
        integer(c,"question-time.random-math.min-number",-1000000,1000000);
        int mathMin=c.getInt("question-time.random-math.min-number");
        integer(c,"question-time.random-math.max-number",mathMin,1000000);
        integer(c,"random-events.interval-seconds.min",1,31536000);
        int eventMin=c.getInt("random-events.interval-seconds.min");
        integer(c,"random-events.interval-seconds.max",eventMin,31536000);
        integer(c,"random-events.duration-seconds",1,31536000);
        number(c,"random-events.mace-shockwave-radius",0.1,64);
        integer(c,"random-events.kill-invisibility-seconds",1,86400);
        for(String event:c.getStringList("random-events.enabled-events")) try {
            com.deruy.plugin.randomevent.RandomEventManager.Type.valueOf(event.toUpperCase(Locale.ROOT));
        } catch(IllegalArgumentException e) { fail("random-events.enabled-events","없는 이벤트: "+event); }
        var contracts=c.getConfigurationSection("risk-contracts.contracts");
        if(contracts!=null) for(String id:contracts.getKeys(false)) {
            choice(c,"risk-contracts.contracts."+id+".type","KILL_PLAYER","KILL_PLAYER","KILL_MOB","FISH","DEAL_DAMAGE");
            number(c,"risk-contracts.contracts."+id+".amount",1,1000000000);
            integer(c,"risk-contracts.contracts."+id+".time-seconds",1,31536000);
        }
        for (String key : List.of("koth","superkoth")) {
            integer(c,key+".required-seconds",1,86400);
            integer(c,key+".zones-visualize-duration-seconds",1,3600);
            for (String time : c.getStringList(key+".schedule.times")) time(time,key+".schedule.times");
        }
        for (String key : List.of("supplydrop.normal","supplydrop.super")) {
            int lower=integer(c,key+".count-min",1,10000);
            integer(c,key+".count-max",lower,10000);
            integer(c,key+".duration-minutes",1,1440);
            integer(c,key+".radius",0,30000000);
        }
        int start=integer(c,"supplydrop.normal.window-start-hour",0,23);
        integer(c,"supplydrop.normal.window-end-hour",start+1,24);
        int triggers=integer(c,"supplydrop.normal.triggers-per-day-min",1,100);
        integer(c,"supplydrop.normal.triggers-per-day-max",triggers,100);
        for(String window:c.getStringList("supplydrop.super.windows")) {
            String[] pair=window.split("-");
            if(pair.length!=2) fail("supplydrop.super.windows","HH:mm-HH:mm 형식");
            LocalTime a=time(pair[0],"supplydrop.super.windows"), b=time(pair[1],"supplydrop.super.windows");
            if(!b.isAfter(a)) fail("supplydrop.super.windows","끝 시각은 시작 시각보다 뒤여야 합니다");
        }
        for (var entry : c.getMapList("role-effects.schedule")) {
            time(String.valueOf(entry.get("time")), "role-effects.schedule.time");
            Object duration=entry.get("duration-seconds"), amplifier=entry.get("amplifier");
            if (duration != null && (!(duration instanceof Number n) || n.doubleValue()!=n.intValue() || n.intValue()<1 || n.intValue()>31536000))
                fail("role-effects.schedule.duration-seconds","1~31536000 사이 정수");
            if (amplifier != null && (!(amplifier instanceof Number n) || n.doubleValue()!=n.intValue() || n.intValue()<0 || n.intValue()>255))
                fail("role-effects.schedule.amplifier","0~255 사이 정수");
        }
        for (var entry : c.getMapList("lifesteal.double-loss-pairs")) {
            Object multiplier=entry.get("multiplier");
            if (!(multiplier instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue()<1 || n.doubleValue()>1024)
                fail("lifesteal.double-loss-pairs.multiplier","1~1024 범위의 숫자");
        }
        var limits=c.getConfigurationSection("item-limits");
        if(limits!=null) for(String key:limits.getKeys(false)) {
            Object raw=limits.get(key);
            if(!(raw instanceof String)) fail("item-limits."+key,"따옴표로 감싼 문자열이 필요합니다");
            try { if(Integer.parseInt(String.valueOf(raw))<0) throw new NumberFormatException(); }
            catch(NumberFormatException e) { fail("item-limits."+key,"0, 00 또는 양의 정수 문자열"); }
        }
        var recipes=c.getConfigurationSection("lifesteal.recipes");
        if(recipes!=null) for(String key:recipes.getKeys(false)) {
            var r=recipes.getConfigurationSection(key); if(r==null) fail("lifesteal.recipes."+key,"설정 섹션이 필요합니다");
            material(r.getString("result-material"),key);
            List<String> shape=r.getStringList("shape");
            if(shape.size()!=3 || shape.stream().anyMatch(s->s.length()!=3)) fail(key+".shape","3×3 모양이어야 합니다");
            var ingredients=r.getConfigurationSection("ingredients");
            if(ingredients==null) fail(key+".ingredients","재료가 필요합니다");
            for(String symbol:ingredients.getKeys(false)) {
                if(symbol.length()!=1 || symbol.equals(" ") || shape.stream().noneMatch(s->s.contains(symbol))) fail(key,"잘못된 재료 기호: "+symbol);
                material(ingredients.getString(symbol),key);
            }
            for(char symbol:String.join("",shape).toCharArray()) if(symbol!=' ' && !ingredients.contains(String.valueOf(symbol))) fail(key,"재료 없는 기호: "+symbol);
        }
        for(String key:c.getKeys(true)) if(key.endsWith(".reward-items")) {
            for(String entry:c.getStringList(key)) reward(c,entry,key);
        }
    }
    private static LocalTime time(String s,String key) {
        try { return LocalTime.parse(s.trim()); } catch(Exception e) { fail(key,"잘못된 시각: "+s); return LocalTime.MIDNIGHT; }
    }
    private static void material(String s,String key) {
        try { Material m=Material.valueOf(s.toUpperCase(Locale.ROOT)); if(!m.isItem()||m.isAir()) fail(key,"아이템 재료가 필요합니다"); }
        catch(Exception e) { fail(key,"존재하지 않는 아이템: "+s); }
    }
    private static void reward(ConfigurationSection c,String entry,String key) {
        String[] parts=entry.split(":",-1); String kind=parts[0].trim().toUpperCase(Locale.ROOT); int offset=1;
        if(kind.equals("RECIPE") || kind.equals("VOUCHER")) {
            if(parts.length<2) fail(key,"잘못된 보상: "+entry);
            offset=2;
            if(kind.equals("RECIPE") && !c.isConfigurationSection("lifesteal.recipes."+parts[1].trim())) fail(key,"없는 레시피: "+entry);
            if(kind.equals("VOUCHER") && !List.of("1","2").contains(parts[1].trim())) fail(key,"교환권 티어는 1 또는 2");
        } else if(!kind.equals("HEART")) material(kind,key);
        if(parts.length>offset+2) fail(key,"보상 형식 오류: "+entry);
        try {
            if(parts.length>offset) { double n=Double.parseDouble(parts[offset]); if(!Double.isFinite(n)||n<0||n>1) throw new NumberFormatException(); }
            if(parts.length>offset+1) { int n=Integer.parseInt(parts[offset+1]); if(n<1||n>2304) throw new NumberFormatException(); }
        } catch(NumberFormatException e) { fail(key,"확률은 0~1, 개수는 1~2304: "+entry); }
    }
}
