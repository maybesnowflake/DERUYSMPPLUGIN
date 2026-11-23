package com.deruy.plugin.transport;

import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.joml.Matrix4f;

import java.io.*;
import java.util.*;

/** Editable, shared geometry. Units are blocks; block coordinates specify centers. */
public final class WagonModel {
    public static final String TAG = "deruy_transport_display";
    public record Part(String name, String kind, Material material, String model,
                       float x, float y, float z, float sx, float sy, float sz,
                       float rx, float ry, float rz, boolean spin) {
        public Part(String name, String kind, Material material, String model, float x, float y, float z,
                    float sx, float sy, float sz, float rx, float ry, float rz) {
            this(name, kind, material, model, x, y, z, sx, sy, sz, rx, ry, rz, false);
        }
        public Matrix4f matrix() { return matrix(0,0,0,0); }
        public Matrix4f matrix(float tx, float ty, float tz) { return matrix(tx,ty,tz,0); }
        /** tilt: 바닥 중심(0,0,0)을 축으로 전체를 기울임(도). spinX: 이 파트 자체를 x축으로 더 돌림(바퀴). */
        public Matrix4f matrix(float tx, float ty, float tz, float spinX) {
            Matrix4f m = new Matrix4f()
                .rotateXYZ((float)Math.toRadians(tx),(float)Math.toRadians(ty),(float)Math.toRadians(tz))
                .translate(x,y,z)
                .rotateXYZ((float)Math.toRadians(rx+spinX),(float)Math.toRadians(ry),(float)Math.toRadians(rz))
                .scale(sx,sy,sz);
            // BlockDisplay has its origin in the corner; ItemDisplay is centered.
            return kind.equals("block") ? m.translate(-.5f,-.5f,-.5f) : m;
        }
    }
    /** 체력 비율이 below 이하일 때 아이템 모델 교체 + 기울기. parts: 파트 이름별 모델 덮어쓰기("none" = 숨김). */
    public record Stage(double below, String model, float tx, float ty, float tz, Map<String,String> parts) {
        public Stage(double below, String model, float tx, float ty, float tz) { this(below, model, tx, ty, tz, Map.of()); }
    }
    /** 움직임 연출: wobble[0]=멀쩡, wobble[i+1]=i번째 손상 단계의 흔들림(도). */
    public record Motion(float[] wobble, double wheelRadius, int spinDirection) {
        public static final Motion NONE = new Motion(new float[]{0}, .5, 1);
        public float wobbleFor(int stage) { return wobble.length == 0 ? 0 : wobble[Math.clamp(stage + 1, 0, wobble.length - 1)]; }
    }
    private final List<Part> parts;
    private final List<Stage> stages;
    private Motion motion = Motion.NONE;
    public Motion motion() { return motion; }
    /** 바퀴나 흔들림이 있을 때만 이동마다 변환을 갱신 (블록 238개 조립판은 갱신 안 함 → 패킷 절약) */
    public boolean animated() { return motion != Motion.NONE || parts.stream().anyMatch(Part::spin); }
    public WagonModel(JavaPlugin plugin) throws IOException {
        File file = new File(plugin.getDataFolder(),"transport-model.yml");
        if (!file.exists()) plugin.saveResource("transport-model.yml",false);
        // 리소스팩 없이 쓰는 블록 조립판 (교체용 참고 파일)
        if (!new File(plugin.getDataFolder(),"transport-model-blocks.yml").exists()) plugin.saveResource("transport-model-blocks.yml",false);
        parts = read(file);
        stages = readStages(file);
        motion = readMotion(file);
    }
    public static Motion readMotion(File file) throws IOException {
        YamlConfiguration c = new YamlConfiguration();
        try { c.load(file); } catch (Exception e) { throw new IOException("모델 설정 오류: "+e.getMessage(),e); }
        if (!c.isConfigurationSection("motion")) return Motion.NONE;
        List<Double> w = c.getDoubleList("motion.wobble-degrees");
        float[] wobble = new float[w.size()];
        for (int i = 0; i < wobble.length; i++) { double v = w.get(i); if (!(v >= 0 && v <= 15)) throw new IOException("motion.wobble-degrees 는 0~15도"); wobble[i] = (float) v; }
        double r = c.getDouble("motion.wheel-radius", .5);
        if (!(r > .05 && r < 5)) throw new IOException("motion.wheel-radius 는 0.05~5");
        int dir = c.getInt("motion.spin-direction", 1);
        if (dir != 1 && dir != -1) throw new IOException("motion.spin-direction 은 1 또는 -1");
        return new Motion(wobble, r, dir);
    }
    /** 굴러간 거리(블록) → 바퀴 회전각(도). */
    public static float spinDegrees(double travelled, double radius, int direction) {
        return (float) (direction * Math.toDegrees(travelled / radius) % 360.0);
    }
    /** 굴러간 거리에 따른 몸체 흔들림 [x(앞뒤), z(좌우)] (도). 두 주기를 섞어 덜 기계적으로. */
    public static float[] wobble(double travelled, float amplitude) {
        if (amplitude <= 0) return new float[]{0, 0};
        return new float[]{(float) (amplitude * .5 * Math.sin(travelled * 3.3 + 1)), (float) (amplitude * Math.sin(travelled * 2.1))};
    }
    /** 이 파트가 이 단계에서 쓸 아이템 모델. null = 숨김. */
    static String modelFor(Part p, Stage st) {
        if (st == null) return p.model;
        String override = st.parts().get(p.name);
        if (override != null) return override.equalsIgnoreCase("none") ? null : override;
        return p.spin || p.model.isEmpty() ? p.model : st.model();
    }
    /** 테스트·외부 도구용 */
    public WagonModel(List<Part> parts, List<Stage> stages) { this.parts=List.copyOf(parts); this.stages=List.copyOf(stages); }
    public List<Stage> stages() { return stages; }
    public static List<Stage> readStages(File file) throws IOException {
        YamlConfiguration c = new YamlConfiguration();
        try { c.load(file); } catch (Exception e) { throw new IOException("모델 설정 오류: "+e.getMessage(),e); }
        List<Stage> out = new ArrayList<>();
        for (Map<?,?> p : c.getMapList("damage-stages")) {
            if(!(p.get("below") instanceof Number b)||!(b.doubleValue()>0&&b.doubleValue()<1)) throw new IOException("damage-stages.below 는 0~1 사이: "+p);
            String model=Objects.toString(p.get("item-model"),"");
            if(NamespacedKey.fromString(model)==null) throw new IOException("damage-stages.item-model 오류: "+p);
            float[] tilt=p.containsKey("tilt")?vector(p.get("tilt")):new float[3];
            for(float t:tilt) if(Math.abs(t)>45) throw new IOException("damage-stages.tilt 는 ±45도 이내");
            Map<String,String> overrides=new LinkedHashMap<>();
            if(p.get("parts") instanceof Map<?,?> m) for(var e:m.entrySet()) {
                String v=Objects.toString(e.getValue(),"");
                if(!v.equalsIgnoreCase("none")&&NamespacedKey.fromString(v)==null) throw new IOException("damage-stages.parts 모델 오류: "+e);
                overrides.put(Objects.toString(e.getKey()),v);
            }
            out.add(new Stage(b.doubleValue(),model,tilt[0],tilt[1],tilt[2],Map.copyOf(overrides)));
        }
        out.sort(Comparator.comparingDouble(Stage::below).reversed());
        return List.copyOf(out);
    }
    /** -1 = 멀쩡, 그 외 stages 순번(below 큰 순). */
    public int stageFor(double ratio) {
        int found=-1;
        for(int i=0;i<stages.size();i++) if(ratio<=stages.get(i).below()) found=i;
        return found;
    }
    /** 손상 단계 모델로 바꾼다(몸체·바퀴·숨김). displays 는 spawn() 이 돌려준 순서 그대로. */
    public void apply(List<Display> displays, int stage) { apply(displays, stage, 0); }
    public void apply(List<Display> displays, int stage, double travelled) {
        Stage st = stage<0||stage>=stages.size()?null:stages.get(stage);
        for(int i=0;i<parts.size()&&i<displays.size();i++) {
            Part p=parts.get(i);
            if(!p.kind.equals("item")||p.model.isEmpty()||!(displays.get(i) instanceof ItemDisplay d)) continue;
            String key=modelFor(p,st);
            ItemStack item=new ItemStack(key==null?Material.AIR:p.material);
            if(key!=null) item.editMeta(m->m.setItemModel(NamespacedKey.fromString(key)));
            d.setItemStack(item);
        }
        pose(displays, stage, travelled, 6);
    }
    /** 이동할 때마다: 바퀴는 굴러간 만큼 돌고, 나머지는 단계별 기울기 + 흔들림. */
    public void pose(List<Display> displays, int stage, double travelled, int interpolationTicks) {
        Stage st = stage<0||stage>=stages.size()?null:stages.get(stage);
        float tx = st==null?0:st.tx(), ty = st==null?0:st.ty(), tz = st==null?0:st.tz();
        float[] w = wobble(travelled, motion.wobbleFor(stage));
        float spin = spinDegrees(travelled, motion.wheelRadius(), motion.spinDirection());
        for(int i=0;i<parts.size()&&i<displays.size();i++) {
            Part p=parts.get(i); Display d=displays.get(i);
            d.setInterpolationDelay(0); d.setInterpolationDuration(interpolationTicks);
            // 바퀴는 땅에 붙어 있어야 하므로 흔들림 없이 기울기만
            d.setTransformationMatrix(p.spin ? p.matrix(tx,ty,tz,spin) : p.matrix(tx+w[0],ty,tz+w[1]));
        }
    }
    public static List<Part> read(File file) throws IOException {
        YamlConfiguration c = new YamlConfiguration();
        try { c.load(file); } catch (Exception e) { throw new IOException("모델 설정 오류: "+e.getMessage(),e); }
        List<Part> result = new ArrayList<>();
        for (Map<?,?> p : c.getMapList("parts")) {
            String kind=Objects.toString(p.get("kind"),"block");
            if (!Set.of("block","item").contains(kind)) throw new IOException("모델 kind 오류");
            Material material=Material.matchMaterial(Objects.toString(p.get("material"),""));
            if (material==null || (kind.equals("block")&&!material.isBlock()) || !material.isItem()) throw new IOException("모델 재료 오류: "+p);
            float[] pos=vector(p.get("position")),scale=vector(p.get("scale")),rot=vector(p.get("rotation"));
            for (float n:scale) if(n<=0||n>16) throw new IOException("모델 scale 범위 오류");
            String model=Objects.toString(p.get("item-model"),"");
            if(!model.isEmpty() && NamespacedKey.fromString(model)==null) throw new IOException("item-model 오류");
            boolean spin=Boolean.TRUE.equals(p.get("spin"));
            result.add(new Part(Objects.toString(p.get("name")),kind,material,model,pos[0],pos[1],pos[2],scale[0],scale[1],scale[2],rot[0],rot[1],rot[2],spin));
        }
        if(result.isEmpty()||result.size()>400)throw new IOException("모델은 1~400개 요소가 필요합니다.");
        return List.copyOf(result);
    }
    private static float[] vector(Object o) throws IOException {
        if(!(o instanceof List<?> l)||l.size()!=3)throw new IOException("3차원 배열이 필요합니다.");
        float[] result=new float[3];
        for(int i=0;i<3;i++) { if(!(l.get(i) instanceof Number n)||!Float.isFinite(n.floatValue()))throw new IOException("유한한 숫자가 필요합니다.");result[i]=n.floatValue(); }
        return result;
    }
    public List<Display> spawn(Location origin) {
        List<Display> displays=new ArrayList<>();
        try {
            for(Part p:parts) {
                Display d;
                if(p.kind.equals("block")) d=origin.getWorld().spawn(origin,BlockDisplay.class,e->e.setBlock(p.material.createBlockData()));
                else {
                    ItemStack item=new ItemStack(p.material);
                    if(!p.model.isEmpty()) item.editMeta(m->m.setItemModel(NamespacedKey.fromString(p.model)));
                    d=origin.getWorld().spawn(origin,ItemDisplay.class,e->{e.setItemStack(item);e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);});
                }
                displays.add(d);
                d.addScoreboardTag(TAG);d.setPersistent(false);d.setInvulnerable(true);d.setGravity(false);
                d.setTransformationMatrix(p.matrix());d.setTeleportDuration(2);
                d.setDisplayWidth(10);d.setDisplayHeight(8);d.setViewRange(1.5f);
            }
            return displays;
        } catch(RuntimeException e){displays.forEach(Entity::remove);throw e;}
    }
}
