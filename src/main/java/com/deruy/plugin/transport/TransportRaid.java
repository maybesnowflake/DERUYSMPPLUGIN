package com.deruy.plugin.transport;
import com.deruy.plugin.DeruyPlugin;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
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

/** Shared hit points across two rectangular Interaction hit boxes. */
public final class TransportRaid implements Listener {
 private final DeruyPlugin plugin;private final TransportManager manager;private final TransportLoot loot;
 private final List<Interaction> boxes=new ArrayList<>();private final Map<UUID,Integer> lastHit=new HashMap<>();private BossBar bar;
 private List<String> attackers,pickers;private List<ItemStack> singles;private boolean inherited,active,requiresHit;private double health,maxHealth,damage,mergeDelay,speed;private int cooldown,pickupTicks;
 public TransportRaid(DeruyPlugin plugin,TransportManager manager,TransportLoot loot){this.plugin=plugin;this.manager=manager;this.loot=loot;}
 public double load() throws IOException {
  File f=new File(plugin.getDataFolder(),"transport-game.yml");if(!f.exists())plugin.saveResource("transport-game.yml",false);YamlConfiguration c=new YamlConfiguration();try{c.load(f);}catch(Exception e){throw new IOException("운송전 설정 오류",e);}
  attackers=TransportGroups.validate(c.getStringList("attack-groups"));pickers=TransportGroups.validate(c.getStringList("pickup-groups"));inherited=c.getBoolean("include-inherited-groups",false);requiresHit=c.getBoolean("pickup-requires-hit",true);
  maxHealth=bounded(c.getDouble("health",200),1,1000000,"health");damage=bounded(c.getDouble("damage-per-hit",10),.1,1000000,"damage-per-hit");speed=bounded(c.getDouble("speed",2),.1,8,"speed");mergeDelay=bounded(c.getDouble("merge-delay-seconds",2),.5,30,"merge-delay-seconds");
  cooldown=c.getInt("hit-cooldown-ticks",10);pickupTicks=c.getInt("pickup-delay-ticks",20);if(cooldown<1||cooldown>200||pickupTicks<0||pickupTicks>1200)throw new IllegalArgumentException("타격/습득 틱 범위 오류");
  List<ItemStack> stacks=new ArrayList<>();for(String entry:c.getStringList("loot")){String[] t=entry.split(":");ItemStack item;int count;
   try{if(t[0].equalsIgnoreCase("VOUCHER")&&t.length==3){int tier=Integer.parseInt(t[1]);if(tier<1||tier>2)throw new IllegalArgumentException("상품권 티어는 1/2");item=plugin.getVoucherManager().createVoucher(tier);count=Integer.parseInt(t[2]);}else if(t.length==2){item=t[0].equalsIgnoreCase("HEART")?plugin.getRecipeManager().createHeartItem():new ItemStack(Material.valueOf(t[0].toUpperCase(Locale.ROOT)));count=Integer.parseInt(t[1]);}else throw new IllegalArgumentException("형식 오류");if(count<1||count>512)throw new IllegalArgumentException("수량 범위 오류");item.setAmount(count);stacks.add(item);}catch(RuntimeException e){throw new IllegalArgumentException("보물 설정 오류: "+entry+" / "+e.getMessage());}
  }
  singles=TransportLoot.split(stacks,512);return speed;
 }
 private static double bounded(double n,double min,double max,String key){if(!Double.isFinite(n)||n<min||n>max)throw new IllegalArgumentException(key+" 범위: "+min+"~"+max);return n;}
 public void begin(Location origin) {
  clear();health=maxHealth;active=true;
  try{for(int i=0;i<2;i++){Interaction box=origin.getWorld().spawn(origin,Interaction.class,e->{e.setInteractionWidth(3);e.setInteractionHeight(2.85f);e.setResponsive(false);e.setGravity(false);e.setPersistent(false);e.addScoreboardTag(WagonModel.TAG);});boxes.add(box);}bar=Bukkit.createBossBar("하트 열차",BarColor.RED,BarStyle.SEGMENTED_10);move(origin);}catch(RuntimeException e){clear();throw e;}
 }
 public boolean owns(UUID id){return boxes.stream().anyMatch(b->b.getUniqueId().equals(id));}
 public boolean valid(){return active&&boxes.size()==2&&boxes.stream().allMatch(Entity::isValid);}
 public boolean active(){return active;}
 public String status(){return active?String.format(Locale.ROOT," | 체력 %.0f/%.0f",health,maxHealth):"";}
 public void move(Location at) {
  if(!active)return;double yaw=Math.toRadians(at.getYaw());for(int i=0;i<boxes.size();i++){double z=i==0?-1:1;Location p=at.clone().add(-Math.sin(yaw)*z,.18,Math.cos(yaw)*z);if(!boxes.get(i).teleport(p))throw new IllegalStateException("공격 판정 이동 실패");}
  bar.setTitle(String.format(Locale.ROOT,"하트 열차 %.0f / %.0f",health,maxHealth));bar.setProgress(Math.clamp(health/maxHealth,0,1));
  for(Player p:Bukkit.getOnlinePlayers()){if(p.getWorld().equals(at.getWorld())&&p.getLocation().distanceSquared(at)<64*64)bar.addPlayer(p);else bar.removePlayer(p);}
 }
 @EventHandler(priority=EventPriority.HIGHEST) public void attack(PrePlayerAttackEntityEvent e) {
  if(!active||boxes.stream().noneMatch(b->b.getUniqueId().equals(e.getAttacked().getUniqueId())))return;
  if(e.isCancelled()&&e.willAttack())return;e.setCancelled(true);Player p=e.getPlayer();if(p.getGameMode()==GameMode.SPECTATOR||!TransportGroups.allows(p,attackers,inherited)){p.sendActionBar(Component.text("열차를 공격할 수 있는 그룹이 아닙니다."));return;}
  BoundingBox b=e.getAttacked().getBoundingBox();Vector eye=p.getEyeLocation().toVector();Vector nearest=new Vector(Math.clamp(eye.getX(),b.getMinX(),b.getMaxX()),Math.clamp(eye.getY(),b.getMinY(),b.getMaxY()),Math.clamp(eye.getZ(),b.getMinZ(),b.getMaxZ()));double distance=eye.distance(nearest);if(distance>4.5)return;
  if(distance>.1){Vector delta=nearest.clone().subtract(eye);var wall=p.getWorld().rayTraceBlocks(p.getEyeLocation(),delta.normalize(),distance,FluidCollisionMode.NEVER,true);if(wall!=null&&wall.getHitPosition().distance(eye)+.05<distance)return;}
  int tick=Bukkit.getCurrentTick();Integer last=lastHit.get(p.getUniqueId());if(last!=null&&tick-last<cooldown)return;lastHit.put(p.getUniqueId(),tick);health=Math.max(0,health-damage);
  Location at=manager.currentLocation();if(at==null)return;at.getWorld().playSound(at,Sound.BLOCK_ANVIL_HIT,.55f,1.6f);at.getWorld().spawnParticle(Particle.CRIT,at.clone().add(0,1.2,0),8,.8,.4,.8,.1);
  if(health==0){List<ItemStack> drop=singles;List<String> access=pickers;boolean inherit=inherited;double delay=mergeDelay;int pickup=pickupTicks;List<String> participants=requiresHit?lastHit.keySet().stream().map(UUID::toString).sorted().toList():null;manager.stop();loot.burst(at,drop,access,inherit,delay,pickup,participants);Bukkit.broadcastMessage("§c♥ [하트 운송전] §e"+p.getName()+"§c의 공격으로 열차가 파괴되었습니다! 보물이 흩어집니다.");}else move(at);
 }
 public void clear(){active=false;boxes.forEach(Entity::remove);boxes.clear();lastHit.clear();if(bar!=null){bar.removeAll();bar=null;}}
}
