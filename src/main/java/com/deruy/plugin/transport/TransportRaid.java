package com.deruy.plugin.transport;
import com.deruy.plugin.DeruyPlugin;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.attribute.Attribute;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.boss.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.bukkit.util.BoundingBox;
import java.io.*;
import java.util.*;

/** Invisible living hit boxes let Minecraft calculate weapon, charge, critical and potion damage. */
public final class TransportRaid implements Listener {
 private final DeruyPlugin plugin;private final TransportManager manager;private final TransportLoot loot;private final TransportRewards rewards;private String awaitingId;private List<String> defenders;private List<ItemStack> arrivalItems;private int claimSeconds;
 private final List<Pig> boxes=new ArrayList<>();private final Map<UUID,Integer> lastHit=new HashMap<>();private BossBar bar;
 private List<String> attackers,pickers;private List<ItemStack> singles;private boolean inherited,active,requiresHit;private double health,maxHealth,mergeDelay,speed;private int pickupTicks;
 public TransportRaid(DeruyPlugin plugin,TransportManager manager,TransportLoot loot){this(plugin,manager,loot,null);}
 public TransportRaid(DeruyPlugin plugin,TransportManager manager,TransportLoot loot,TransportRewards rewards){this.plugin=plugin;this.manager=manager;this.loot=loot;this.rewards=rewards;}
 public double load() throws IOException {
  File f=new File(plugin.getDataFolder(),"transport-game.yml");if(!f.exists())plugin.saveResource("transport-game.yml",false);YamlConfiguration c=new YamlConfiguration();try{c.load(f);}catch(Exception e){throw new IOException("운송전 설정 오류",e);}
  // Explicit config.yml transport keys override the legacy file; missing keys keep old values.
  // Ignore embedded defaults here to preserve existing servers' transport-game.yml settings.
  var main=plugin.getConfig();
  for(String key:List.of("health","loot","arrival-rewards","attack-groups","pickup-groups","defender-groups","arrival-claim-seconds","include-inherited-groups","pickup-requires-hit","speed","merge-delay-seconds","pickup-delay-ticks"))
   if(main.contains("transport."+key,true))c.set(key,main.get("transport."+key));
  attackers=TransportGroups.validate(c.getStringList("attack-groups"));pickers=TransportGroups.validate(c.getStringList("pickup-groups"));inherited=c.getBoolean("include-inherited-groups",false);requiresHit=c.getBoolean("pickup-requires-hit",true);defenders=TransportGroups.validate(c.getStringList("defender-groups"));claimSeconds=c.getInt("arrival-claim-seconds",180);if(claimSeconds<1||claimSeconds>86400)throw new IllegalArgumentException("arrival-claim-seconds: 1~86400");
  maxHealth=bounded(c.getDouble("health",200),1,1000000,"health");speed=bounded(c.getDouble("speed",2),.1,8,"speed");mergeDelay=bounded(c.getDouble("merge-delay-seconds",2),.5,30,"merge-delay-seconds");
  pickupTicks=c.getInt("pickup-delay-ticks",20);if(pickupTicks<0||pickupTicks>1200)throw new IllegalArgumentException("타격/습득 틱 범위 오류");
  singles=parseItems(c.getStringList("loot"));arrivalItems=c.contains("arrival-rewards")?parseItems(c.getStringList("arrival-rewards")):singles;return speed;
 }
 private List<ItemStack> parseItems(List<String> entries){
  List<ItemStack> stacks=new ArrayList<>();for(String entry:entries){String[] t=entry.split(":");ItemStack item;int count;
   try{if(t[0].equalsIgnoreCase("VOUCHER")&&t.length==3){int tier=Integer.parseInt(t[1]);if(tier<1||tier>2)throw new IllegalArgumentException("상품권 티어는 1/2");item=plugin.getVoucherManager().createVoucher(tier);count=Integer.parseInt(t[2]);}else if(t.length==2){item=t[0].equalsIgnoreCase("HEART")?plugin.getRecipeManager().createHeartItem():new ItemStack(Material.valueOf(t[0].toUpperCase(Locale.ROOT)));count=Integer.parseInt(t[1]);}else throw new IllegalArgumentException("형식 오류");if(count<1||count>512)throw new IllegalArgumentException("수량 범위 오류");item.setAmount(count);stacks.add(item);}catch(RuntimeException e){throw new IllegalArgumentException("보물 설정 오류: "+entry+" / "+e.getMessage());}
  }
  return TransportLoot.split(stacks,512);
 }
 public boolean awaiting(){return awaitingId!=null;}
 public boolean awaiting(String id){return Objects.equals(awaitingId,id);}
 public void arrive(Location at) throws IOException {if(!active)return;if(rewards==null){clear();return;}awaitingId=rewards.create(at,arrivalItems,defenders,inherited,claimSeconds);if(bar!=null){bar.setColor(BarColor.GREEN);bar.setTitle("호위 성공! 열차를 우클릭해서 보상을 수령하세요.");}Bukkit.broadcastMessage("§a[하트 운송전] 호위 그룹은 열차를 우클릭해서 보상을 가져가세요. "+claimSeconds+"초 후 남은 보상은 전용 상자로 옮겨집니다.");}
 private static double bounded(double n,double min,double max,String key){if(!Double.isFinite(n)||n<min||n>max)throw new IllegalArgumentException(key+" 범위: "+min+"~"+max);return n;}
 public void begin(Location origin) {
  clear();health=maxHealth;active=true;
  try{for(int i=0;i<2;i++){Pig box=origin.getWorld().spawn(origin,Pig.class,e->{e.setAdult();e.setAgeLock(true);e.setBreed(false);e.setAI(false);e.setCollidable(false);e.setSilent(true);e.setInvisible(true);e.setGravity(false);e.setPersistent(false);e.setRemoveWhenFarAway(false);e.getAttribute(Attribute.SCALE).setBaseValue(3.2);e.getAttribute(Attribute.MAX_HEALTH).setBaseValue(1024);e.setHealth(1024);e.addScoreboardTag(WagonModel.TAG);});boxes.add(box);}bar=Bukkit.createBossBar("하트 열차",BarColor.RED,BarStyle.SEGMENTED_10);move(origin);}catch(RuntimeException e){clear();throw e;}
 }
 public boolean owns(UUID id){return boxes.stream().anyMatch(b->b.getUniqueId().equals(id));}
 public boolean valid(){return active&&boxes.size()==2&&boxes.stream().allMatch(Entity::isValid);}
 public boolean active(){return active;}
 public String status(){return active?String.format(Locale.ROOT," | 체력 %.0f/%.0f",health,maxHealth):"";}
 public void move(Location at) {
  if(!active)return;double yaw=Math.toRadians(at.getYaw());for(int i=0;i<boxes.size();i++){double z=i==0?-1:1;Location p=at.clone().add(-Math.sin(yaw)*z,.18,Math.cos(yaw)*z);boxes.get(i).setFireTicks(0);if(!boxes.get(i).teleport(p))throw new IllegalStateException("공격 판정 이동 실패");}
  bar.setTitle(String.format(Locale.ROOT,"하트 열차 %.0f / %.0f",health,maxHealth));bar.setProgress(Math.clamp(health/maxHealth,0,1));
  for(Player p:Bukkit.getOnlinePlayers()){if(p.getWorld().equals(at.getWorld())&&p.getLocation().distanceSquared(at)<64*64)bar.addPlayer(p);else bar.removePlayer(p);}
 }
 @EventHandler(priority=EventPriority.HIGHEST) public void blockInteraction(PlayerInteractEntityEvent e){if(!owns(e.getRightClicked().getUniqueId()))return;e.setCancelled(true);if(awaitingId!=null&&e.getHand()==org.bukkit.inventory.EquipmentSlot.HAND)rewards.claim(awaitingId,e.getPlayer());}
 @EventHandler(priority=EventPriority.HIGHEST) public void attack(EntityDamageEvent e) {
  if(!active||!owns(e.getEntity().getUniqueId()))return;
  boolean cancelled=e.isCancelled();double dealt=e.getFinalDamage();e.setCancelled(true);
  if(awaiting()||cancelled||!Double.isFinite(dealt)||dealt<=0||!(e instanceof EntityDamageByEntityEvent by))return;
  Player p=by.getDamager() instanceof Player direct?direct:by.getDamager() instanceof Projectile projectile&&projectile.getShooter() instanceof Player shooter?shooter:null;
  if(p==null)return;
  if(p.getGameMode()==GameMode.SPECTATOR||!TransportGroups.allows(p,attackers,inherited)){p.sendActionBar(Component.text("열차를 공격할 수 있는 그룹이 아닙니다."));return;}
  int tick=Bukkit.getCurrentTick();Integer last=lastHit.get(p.getUniqueId());
  // Both invisible bodies share one target: a sweep must not damage it twice.
  if(last!=null&&tick==last)return;lastHit.put(p.getUniqueId(),tick);health=Math.max(0,health-dealt);
  Location at=manager.currentLocation();if(at==null)return;at.getWorld().playSound(at,Sound.BLOCK_ANVIL_HIT,.55f,1.6f);at.getWorld().spawnParticle(Particle.CRIT,at.clone().add(0,1.2,0),8,.8,.4,.8,.1);
  if(health==0){List<ItemStack> drop=singles;List<String> access=pickers;boolean inherit=inherited;double delay=mergeDelay;int pickup=pickupTicks;List<String> participants=requiresHit?lastHit.keySet().stream().map(UUID::toString).sorted().toList():null;manager.stop();loot.burst(at,drop,access,inherit,delay,pickup,participants);Bukkit.broadcastMessage("§c♥ [하트 운송전] §e"+p.getName()+"§c의 공격으로 열차가 파괴되었습니다! 보물이 흩어집니다.");}else move(at);
 }
 public void clear(){awaitingId=null;active=false;boxes.forEach(Entity::remove);boxes.clear();lastHit.clear();if(bar!=null){bar.removeAll();bar=null;}}
}
