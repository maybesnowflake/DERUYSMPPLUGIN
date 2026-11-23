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
                       float rx, float ry, float rz) {
        public Matrix4f matrix() { return matrix(0,0,0); }
        /** tilt: 바닥 중심(0,0,0)을 축으로 전체를 기울임(도). */
        public Matrix4f matrix(float tx, float ty, float tz) {
            Matrix4f m = new Matrix4f()
                .rotateXYZ((float)Math.toRadians(tx),(float)Math.toRadians(ty),(float)Math.toRadians(tz))
                .translate(x,y,z)
                .rotateXYZ((float)Math.toRadians(rx),(float)Math.toRadians(ry),(float)Math.toRadians(rz))
                .scale(sx,sy,sz);
            // BlockDisplay has its origin in the corner; ItemDisplay is centered.
            return kind.equals("block") ? m.translate(-.5f,-.5f,-.5f) : m;
        }
    }
    /** 체력 비율이 below 이하일 때 아이템 모델 교체 + 기울기. */
    public record Stage(double below, String model, float tx, float ty, float tz) {}
    private final List<Part> parts;
    private final List<Stage> stages;
    public WagonModel(JavaPlugin plugin) throws IOException {
        File file = new File(plugin.getDataFolder(),"transport-model.yml");
        if (!file.exists()) plugin.saveResource("transport-model.yml",false);
        // 리소스팩 없이 쓰는 블록 조립판 (교체용 참고 파일)
        if (!new File(plugin.getDataFolder(),"transport-model-blocks.yml").exists()) plugin.saveResource("transport-model-blocks.yml",false);
        parts = read(file);
        stages = readStages(file);
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
            out.add(new Stage(b.doubleValue(),model,tilt[0],tilt[1],tilt[2]));
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
    /** 아이템 모델이 지정된 파트만 손상 단계 모델로 바꾼다. displays 는 spawn() 이 돌려준 순서 그대로. */
    public void apply(List<Display> displays, int stage) {
        Stage st = stage<0||stage>=stages.size()?null:stages.get(stage);
        for(int i=0;i<parts.size()&&i<displays.size();i++) {
            Part p=parts.get(i);
            if(!p.kind.equals("item")||p.model.isEmpty()||!(displays.get(i) instanceof ItemDisplay d)) continue;
            ItemStack item=new ItemStack(p.material);
            String key=st==null?p.model:st.model();
            item.editMeta(m->m.setItemModel(NamespacedKey.fromString(key)));
            d.setItemStack(item);
            d.setInterpolationDelay(0); d.setInterpolationDuration(6);
            d.setTransformationMatrix(st==null?p.matrix():p.matrix(st.tx(),st.ty(),st.tz()));
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
            result.add(new Part(Objects.toString(p.get("name")),kind,material,model,pos[0],pos[1],pos[2],scale[0],scale[1],scale[2],rot[0],rot[1],rot[2]));
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
