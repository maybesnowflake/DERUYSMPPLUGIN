package com.deruy.plugin.contract;
import com.deruy.plugin.DeruyPlugin;import org.bukkit.*;import org.bukkit.configuration.ConfigurationSection;import org.bukkit.entity.*;import org.bukkit.event.*;import org.bukkit.event.entity.*;import org.bukkit.event.player.*;import org.bukkit.scheduler.BukkitTask;import java.util.*;
/** 설정형 위험 계약: 제한 시간 안에 처치·피해·낚시·생존 목표를 달성한다. */
public final class ContractManager implements Listener{
 private record Active(String id,String type,double target,double progress,BukkitTask timeout){}
 private final DeruyPlugin plugin;private final Map<UUID,Active> active=new HashMap<>();public ContractManager(DeruyPlugin p){plugin=p;}
 public Set<String> ids(){ConfigurationSection s=plugin.getConfig().getConfigurationSection("risk-contracts.contracts");return s==null?Set.of():s.getKeys(false);}
 public boolean accept(Player p,String id){if(active.containsKey(p.getUniqueId()))return false;ConfigurationSection s=plugin.getConfig().getConfigurationSection("risk-contracts.contracts."+id);if(s==null)return false;String type=s.getString("type","KILL_PLAYER").toUpperCase();double goal=Math.max(1,s.getDouble("amount",1));long sec=Math.max(1,s.getLong("time-seconds",300));BukkitTask task=Bukkit.getScheduler().runTaskLater(plugin,()->fail(p.getUniqueId(),"시간 초과"),sec*20L);active.put(p.getUniqueId(),new Active(id,type,goal,0,task));p.sendMessage("§4[위험 계약] §e"+id+" 계약을 수락했습니다. 제한시간: "+sec+"초");return true;}
 public String status(Player p){Active a=active.get(p.getUniqueId());return a==null?"진행 중인 계약 없음":a.id+" "+a.type+" "+trim(a.progress)+"/"+trim(a.target);}
 public void cancel(Player p){Active a=active.remove(p.getUniqueId());if(a!=null)a.timeout.cancel();}
 private String trim(double d){return d==(long)d?Long.toString((long)d):String.format(Locale.ROOT,"%.1f",d);}
 private void add(Player p,String type,double n){Active a=active.get(p.getUniqueId());if(a==null||!a.type.equals(type))return;double progress=a.progress+n;if(progress>=a.target){a.timeout.cancel();active.remove(p.getUniqueId());p.sendMessage("§4§l[위험 계약] §a계약 성공!");var items=plugin.getSupplyChestRegistry().rollRewards(plugin.getConfig().getStringList("risk-contracts.contracts."+a.id+".reward-items"),plugin.getLogger());items.forEach(i->p.getInventory().addItem(i).values().forEach(left->p.getWorld().dropItemNaturally(p.getLocation(),left)));}else active.put(p.getUniqueId(),new Active(a.id,a.type,a.target,progress,a.timeout));}
 private void fail(UUID id,String why){Player p=Bukkit.getPlayer(id);active.remove(id);if(p!=null)p.sendMessage("§4[위험 계약] §c실패: "+why);}
 @EventHandler public void death(EntityDeathEvent e){Player k=e.getEntity().getKiller();if(k==null)return;add(k,e.getEntity() instanceof Player?"KILL_PLAYER":"KILL_MOB",1);}
 @EventHandler(ignoreCancelled=true)public void damage(EntityDamageByEntityEvent e){Player p=e.getDamager() instanceof Player x?x:e.getDamager() instanceof Projectile q&&q.getShooter() instanceof Player x?x:null;if(p!=null)add(p,"DEAL_DAMAGE",e.getFinalDamage());}
 @EventHandler public void fish(PlayerFishEvent e){if(e.getState()==PlayerFishEvent.State.CAUGHT_FISH)add(e.getPlayer(),"FISH",1);}
 public void stop(){active.values().forEach(a->a.timeout.cancel());active.clear();}
}
