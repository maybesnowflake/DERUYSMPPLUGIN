package com.deruy.plugin.transport;
import com.deruy.plugin.DeruyPlugin;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import java.util.*;
/** Restriction persists on dropped entities; inventory stacks keep their original item data. */
public final class TransportLoot implements Listener {
 static final NamespacedKey PARTICIPANTS=new NamespacedKey("deruy","transport_loot_participants");
 static final NamespacedKey GROUPS=new NamespacedKey("deruy","transport_loot_groups"),MERGE_AT=new NamespacedKey("deruy","transport_loot_merge_at"),INHERITED=new NamespacedKey("deruy","transport_loot_inherited");
 public TransportLoot(DeruyPlugin plugin){}
 public static List<ItemStack> split(List<ItemStack> stacks,int cap) {
  int total=0;for(ItemStack s:stacks){if(s==null||s.getType().isAir()||s.getAmount()<1)throw new IllegalArgumentException("잘못된 보물 아이템");total=Math.addExact(total,s.getAmount());}
  if(total<1||total>cap)throw new IllegalArgumentException("분산 드롭 수량은 1~"+cap+"개여야 합니다.");
  List<ItemStack> singles=new ArrayList<>(total);for(ItemStack s:stacks)for(int i=0;i<s.getAmount();i++){ItemStack one=s.clone();one.setAmount(1);singles.add(one);}return List.copyOf(singles);
 }
 static boolean mergeAllowed(String left,String right,long leftAt,long rightAt,long now){return Objects.equals(left,right)&&now>=leftAt&&now>=rightAt;}
 public void burst(Location center,List<ItemStack> singles,List<String> groups,boolean inherited,double delay,int pickupTicks,List<String> participants) {
  long until=System.currentTimeMillis()+Math.round(delay*1000);String restriction=String.join(",",groups);Random rng=new Random();
  for(int i=0;i<singles.size();i++) {
   double angle=i*Math.PI*2/singles.size()+rng.nextDouble()*.2,speed=.22+rng.nextDouble()*.28;Vector velocity=new Vector(Math.cos(angle)*speed,.30+rng.nextDouble()*.28,Math.sin(angle)*speed);
   Location at=center.clone().add(Math.cos(angle)*.25,1.4+rng.nextDouble()*.35,Math.sin(angle)*.25);
   center.getWorld().dropItem(at,singles.get(i).clone(),item->{var d=item.getPersistentDataContainer();d.set(GROUPS,PersistentDataType.STRING,restriction);if(participants!=null)d.set(PARTICIPANTS,PersistentDataType.STRING,String.join(",",participants));d.set(INHERITED,PersistentDataType.BYTE,(byte)(inherited?1:0));d.set(MERGE_AT,PersistentDataType.LONG,until);item.setPickupDelay(pickupTicks);item.setVelocity(velocity);item.setInvulnerable(true);});
  }
  center.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER,center.clone().add(0,1.4,0),1);center.getWorld().playSound(center,Sound.ENTITY_GENERIC_EXPLODE,1.0f,1.15f);
 }
 private static String groups(Item i){return i.getPersistentDataContainer().get(GROUPS,PersistentDataType.STRING);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void onMerge(ItemMergeEvent e) {
  String a=groups(e.getEntity()),b=groups(e.getTarget());if(a==null&&b==null)return;
  var l=e.getEntity().getPersistentDataContainer();var r=e.getTarget().getPersistentDataContainer();long la=l.getOrDefault(MERGE_AT,PersistentDataType.LONG,Long.MAX_VALUE),ra=r.getOrDefault(MERGE_AT,PersistentDataType.LONG,Long.MAX_VALUE);
  byte li=l.getOrDefault(INHERITED,PersistentDataType.BYTE,(byte)1),ri=r.getOrDefault(INHERITED,PersistentDataType.BYTE,(byte)1);
  if(a==null||b==null||li!=ri||!Objects.equals(l.get(PARTICIPANTS,PersistentDataType.STRING),r.get(PARTICIPANTS,PersistentDataType.STRING))||!mergeAllowed(a,b,la,ra,System.currentTimeMillis()))e.setCancelled(true);
 }
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void onPickup(EntityPickupItemEvent e) {
  String s=groups(e.getItem());if(s==null)return;boolean inherited=e.getItem().getPersistentDataContainer().getOrDefault(INHERITED,PersistentDataType.BYTE,(byte)1)==1;
  String participants=e.getItem().getPersistentDataContainer().get(PARTICIPANTS,PersistentDataType.STRING);
  if(!(e.getEntity() instanceof Player p)||(participants!=null&&!List.of(participants.split(",")).contains(p.getUniqueId().toString()))||!TransportGroups.allows(p,List.of(s.split(",")),inherited))e.setCancelled(true);
 }
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void onHopper(InventoryPickupItemEvent e){if(groups(e.getItem())!=null)e.setCancelled(true);}
}
