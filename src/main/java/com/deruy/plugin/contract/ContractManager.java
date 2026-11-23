package com.deruy.plugin.contract;
import com.deruy.plugin.DeruyPlugin;import org.bukkit.*;import org.bukkit.configuration.ConfigurationSection;import org.bukkit.entity.*;import org.bukkit.event.*;import org.bukkit.event.entity.*;import org.bukkit.event.player.*;import org.bukkit.scheduler.BukkitTask;import java.util.*;
/** 설정형 위험 계약: 제한 시간 안에 처치·피해·낚시·생존 목표를 달성한다. */
public final class ContractManager implements Listener{
 private record Active(String id,String type,double target,double progress,BukkitTask timeout,long deadline){}
 private final DeruyPlugin plugin;private final Map<UUID,Active> active=new HashMap<>();
 /** 나가 있는 동안 시간이 끝난 계약: 다음 접속 때 알려줌 */
 private final Set<UUID> expiredWhileAway=new HashSet<>();
 public ContractManager(DeruyPlugin p){plugin=p;restore();}
 /** 재시작해도 계약 유지. 제한시간은 실제 시각 기준(나가 있어도 흐름) */
 private void restore(){var sec=plugin.getDataStore()==null?null:plugin.getDataStore().section("contracts");if(sec==null)return;long now=System.currentTimeMillis();
  for(String k:sec.getKeys(false)){try{UUID id=UUID.fromString(k);var c=sec.getConfigurationSection(k);long dl=c.getLong("deadline");if(dl<=now){expiredWhileAway.add(id);continue;}
   BukkitTask t=Bukkit.getScheduler().runTaskLater(plugin,()->fail(id,"시간 초과"),Math.max(1,(dl-now)/50));
   active.put(id,new Active(c.getString("id"),c.getString("type"),c.getDouble("target"),c.getDouble("progress"),t,dl));}catch(RuntimeException e){plugin.getLogger().warning("저장된 위험 계약을 읽지 못함: "+k);}}
  plugin.getDataStore().set("contracts",null);active.forEach(this::persist);plugin.getDataStore().save();}
 private void persist(UUID id,Active a){var ds=plugin.getDataStore();if(ds==null)return;String b="contracts."+id+".";ds.set(b+"id",a.id);ds.set(b+"type",a.type);ds.set(b+"target",a.target);ds.set(b+"progress",a.progress);ds.set(b+"deadline",a.deadline);}
 private void unpersist(UUID id){var ds=plugin.getDataStore();if(ds==null)return;ds.set("contracts."+id,null);ds.save();}
 @EventHandler public void join(org.bukkit.event.player.PlayerJoinEvent e){if(expiredWhileAway.remove(e.getPlayer().getUniqueId()))e.getPlayer().sendMessage("§4[위험 계약] §c실패: 접속하지 않은 동안 제한시간이 끝났습니다.");}
 public boolean hasPending(UUID id){return active.containsKey(id);}
 public Set<String> ids(){ConfigurationSection s=plugin.getConfig().getConfigurationSection("risk-contracts.contracts");return s==null?Set.of():s.getKeys(false);}
 public boolean accept(Player p,String id){if(active.containsKey(p.getUniqueId()))return false;ConfigurationSection s=plugin.getConfig().getConfigurationSection("risk-contracts.contracts."+id);if(s==null)return false;String type=s.getString("type","KILL_PLAYER").toUpperCase();double goal=Math.max(1,s.getDouble("amount",1));long sec=Math.max(1,s.getLong("time-seconds",300));UUID pid=p.getUniqueId();BukkitTask task=Bukkit.getScheduler().runTaskLater(plugin,()->fail(pid,"시간 초과"),sec*20L);Active a=new Active(id,type,goal,0,task,System.currentTimeMillis()+sec*1000L);active.put(pid,a);persist(pid,a);plugin.getDataStore().save();p.sendMessage("§4[위험 계약] §e"+id+" 계약을 수락했습니다. 제한시간: "+sec+"초");return true;}
 public String status(Player p){Active a=active.get(p.getUniqueId());return a==null?"진행 중인 계약 없음":a.id+" "+a.type+" "+trim(a.progress)+"/"+trim(a.target);}
 public void cancel(Player p){Active a=active.remove(p.getUniqueId());if(a!=null){a.timeout.cancel();unpersist(p.getUniqueId());}}
 private String trim(double d){return d==(long)d?Long.toString((long)d):String.format(Locale.ROOT,"%.1f",d);}
 private void add(Player p,String type,double n){Active a=active.get(p.getUniqueId());if(a==null||!a.type.equals(type))return;double progress=a.progress+n;if(progress>=a.target){a.timeout.cancel();active.remove(p.getUniqueId());unpersist(p.getUniqueId());p.sendMessage("§4§l[위험 계약] §a계약 성공!");var items=plugin.getSupplyChestRegistry().rollRewards(plugin.getConfig().getStringList("risk-contracts.contracts."+a.id+".reward-items"),plugin.getLogger());items.forEach(i->p.getInventory().addItem(i).values().forEach(left->p.getWorld().dropItemNaturally(p.getLocation(),left)));}else{Active next=new Active(a.id,a.type,a.target,progress,a.timeout,a.deadline);active.put(p.getUniqueId(),next);persist(p.getUniqueId(),next);}}
 private void fail(UUID id,String why){Player p=Bukkit.getPlayer(id);if(active.remove(id)!=null)unpersist(id);if(p!=null)p.sendMessage("§4[위험 계약] §c실패: "+why);else expiredWhileAway.add(id);}
 @EventHandler public void death(EntityDeathEvent e){Player k=e.getEntity().getKiller();if(k==null)return;add(k,e.getEntity() instanceof Player?"KILL_PLAYER":"KILL_MOB",1);}
 @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void damage(EntityDamageByEntityEvent e){Player p=e.getDamager() instanceof Player x?x:e.getDamager() instanceof Projectile q&&q.getShooter() instanceof Player x?x:null;if(p!=null)add(p,"DEAL_DAMAGE",e.getFinalDamage());}
 @EventHandler public void fish(PlayerFishEvent e){if(e.getState()==PlayerFishEvent.State.CAUGHT_FISH)add(e.getPlayer(),"FISH",1);}
 /** 서버 종료: 타이머만 멈추고 저장은 유지 (진행도는 바뀔 때마다 저장, 종료 때 한 번 더) */
 public void stop(){var ds=plugin.getDataStore();if(ds!=null){active.forEach(this::persist);ds.save();}active.values().forEach(a->a.timeout.cancel());active.clear();}
}
