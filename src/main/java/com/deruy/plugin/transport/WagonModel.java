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
        public Matrix4f matrix() {
            Matrix4f m = new Matrix4f().translate(x,y,z)
                .rotateXYZ((float)Math.toRadians(rx),(float)Math.toRadians(ry),(float)Math.toRadians(rz))
                .scale(sx,sy,sz);
            // BlockDisplay has its origin in the corner; ItemDisplay is centered.
            return kind.equals("block") ? m.translate(-.5f,-.5f,-.5f) : m;
        }
    }
    private final List<Part> parts;
    public WagonModel(JavaPlugin plugin) throws IOException {
        File file = new File(plugin.getDataFolder(),"transport-model.yml");
        if (!file.exists()) plugin.saveResource("transport-model.yml",false);
        parts = read(file);
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
