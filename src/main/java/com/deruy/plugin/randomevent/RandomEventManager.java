package com.deruy.plugin.randomevent;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.potion.*;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** 한 번에 하나씩 실행되는 서버 랜덤 규칙 이벤트. */
public final class RandomEventManager implements Listener {
    public enum Type { SHIELD_BAN,TOTEM_BAN,POTION_BAN,POISON_HIT,DOUBLE_DROPS,MACE_BAN,STRENGTH_III,KILL_DROP,CROP_REWARD,SWORD_SHIELD_BREAK,AXE_CRIT_SLOW,TNT_DOUBLE_DAMAGE,PROJECTILE_BAN,KILL_INVISIBILITY,KILL_VOUCHER,MACE_DOUBLE_DAMAGE,PROJECTILE_WITHER,COBWEB_BAN,LIFESTEAL,COUNTERATTACK,POST_KILL_SPEED,FISHING_FRENZY,MACE_SHOCKWAVE }
    private final DeruyPlugin plugin;
    private final Set<String> placedBlocks = new HashSet<>();
    private Type active; private BukkitTask endTask,nextTask,shieldTask;private EventRoulette roulette; private boolean scheduler;
    public RandomEventManager(DeruyPlugin plugin){this.plugin=plugin;}
    public Type getActive(){return active;} public boolean isSchedulerEnabled(){return scheduler;}
    public boolean isDrawing(){return roulette!=null;}
    public void startScheduler(){scheduler=true;if(active==null&&!isDrawing())scheduleNext();}
    public void stopScheduler(){scheduler=false;stopActive();if(nextTask!=null)nextTask.cancel();}
    private void scheduleNext(){if(nextTask!=null){nextTask.cancel();nextTask=null;}if(!scheduler)return;long min=Math.max(1,plugin.getConfig().getLong("random-events.interval-seconds.min",900));long max=Math.max(min,plugin.getConfig().getLong("random-events.interval-seconds.max",1800));nextTask=Bukkit.getScheduler().runTaskLater(plugin,this::startRandom,ThreadLocalRandom.current().nextLong(min,max+1)*20L);}
    public List<Type> enabledTypes(){List<Type> out=new ArrayList<>();for(String name:plugin.getConfig().getStringList("random-events.enabled-events"))try{Type t=Type.valueOf(name.toUpperCase(Locale.ROOT));if(!out.contains(t))out.add(t);}catch(IllegalArgumentException ignored){}return out;}
    public boolean startRandom(){List<Type> enabled=enabledTypes();if(enabled.isEmpty())return false;stopActive();if(nextTask!=null){nextTask.cancel();nextTask=null;}roulette=new EventRoulette(plugin,enabled,t->{roulette=null;start(t);});roulette.start();return true;}
    public boolean start(Type type){stopActive();if(nextTask!=null){nextTask.cancel();nextTask=null;}active=type;long sec=Math.max(1,plugin.getConfig().getLong("random-events.duration-seconds",300));Bukkit.broadcastMessage("§c§l[랜덤 이벤트] §f"+display(type)+" §7("+sec+"초)");if(type==Type.POTION_BAN)Bukkit.getOnlinePlayers().forEach(p->p.getActivePotionEffects().forEach(e->p.removePotionEffect(e.getType())));applyPersistent();if(type==Type.SHIELD_BAN){enforceShields();shieldTask=Bukkit.getScheduler().runTaskTimer(plugin,this::enforceShields,1,2);}endTask=Bukkit.getScheduler().runTaskLater(plugin,()->{stopActive();scheduleNext();},sec*20L);return true;}
    public void stopActive(){if(roulette!=null){roulette.cancel();roulette=null;}if(shieldTask!=null){shieldTask.cancel();shieldTask=null;}if(active==Type.SHIELD_BAN)for(Player p:Bukkit.getOnlinePlayers())if(p.getCooldown(Material.SHIELD)<=4)p.setCooldown(Material.SHIELD,0);if(endTask!=null){endTask.cancel();endTask=null;}if(active!=null)Bukkit.broadcastMessage("§c[랜덤 이벤트] §7이벤트가 종료되었습니다.");active=null;Bukkit.getOnlinePlayers().forEach(p->{p.removePotionEffect(PotionEffectType.STRENGTH);});}
    private String display(Type t){return plugin.getConfig().getString("random-events.names."+t.name(),t.name());}
    private void applyPersistent(){if(active==Type.STRENGTH_III)Bukkit.getOnlinePlayers().forEach(p->p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,Integer.MAX_VALUE,2,false,false,true)));}
    private void enforceShields(){if(active!=Type.SHIELD_BAN)return;for(Player p:Bukkit.getOnlinePlayers()){if(p.hasActiveItem()&&p.getActiveItem().getType()==Material.SHIELD)p.clearActiveItem();if(p.getCooldown(Material.SHIELD)<4)p.setCooldown(Material.SHIELD,4);}}
    private boolean on(Type t){return active==t;}
    private Player attacker(Entity e){if(e instanceof Player p)return p;if(e instanceof Projectile p&&p.getShooter() instanceof Player shooter)return shooter;return null;}
    private boolean weapon(Player p,Material m){return p.getInventory().getItemInMainHand().getType()==m;}
    private boolean suffix(Player p,String s){return p.getInventory().getItemInMainHand().getType().name().endsWith(s);}
    private String key(Block b){return b.getWorld().getUID()+":"+b.getX()+":"+b.getY()+":"+b.getZ();}
    private void deny(Cancellable e,Player p,String text){e.setCancelled(true);p.sendMessage("§c[랜덤 이벤트] "+text);}

    @EventHandler public void rouletteClick(org.bukkit.event.inventory.InventoryClickEvent e){if(e.getView().getTopInventory().getHolder() instanceof EventRoulette.Gui)e.setCancelled(true);}
    @EventHandler public void rouletteDrag(org.bukkit.event.inventory.InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof EventRoulette.Gui)e.setCancelled(true);}
    @EventHandler public void join(PlayerJoinEvent e){applyPersistent();}
    @EventHandler(ignoreCancelled=true) public void place(BlockPlaceEvent e){placedBlocks.add(key(e.getBlockPlaced()));if(on(Type.COBWEB_BAN)&&e.getBlockPlaced().getType()==Material.COBWEB)deny(e,e.getPlayer(),"거미줄을 사용할 수 없습니다.");}
    @EventHandler(ignoreCancelled=true) public void consume(PlayerItemConsumeEvent e){if(on(Type.POTION_BAN)&&(e.getItem().getType()==Material.POTION||e.getItem().getType()==Material.SPLASH_POTION||e.getItem().getType()==Material.LINGERING_POTION))deny(e,e.getPlayer(),"포션을 사용할 수 없습니다.");}
    @EventHandler(ignoreCancelled=true) public void resurrect(EntityResurrectEvent e){if(on(Type.TOTEM_BAN)&&e.getEntity() instanceof Player p)deny(e,p,"토템을 사용할 수 없습니다.");}
    @EventHandler(ignoreCancelled=true) public void launch(ProjectileLaunchEvent e){if(!(e.getEntity().getShooter() instanceof Player p))return;if(on(Type.POTION_BAN)&&e.getEntity() instanceof ThrownPotion){deny(e,p,"포션을 사용할 수 없습니다.");return;}if(on(Type.PROJECTILE_BAN))deny(e,p,"투사체를 사용할 수 없습니다.");}
    @EventHandler(priority=EventPriority.HIGHEST) public void interact(PlayerInteractEvent e){Material item=e.getMaterial();if(on(Type.SHIELD_BAN)&&item==Material.SHIELD){e.setUseItemInHand(Event.Result.DENY);deny(e,e.getPlayer(),"방패를 사용할 수 없습니다.");e.getPlayer().clearActiveItem();e.getPlayer().setCooldown(Material.SHIELD,4);}if(on(Type.MACE_BAN)&&item==Material.MACE)deny(e,e.getPlayer(),"철퇴를 사용할 수 없습니다.");}

    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void damage(EntityDamageByEntityEvent e){
        Player p=attacker(e.getDamager());
        if(e.getDamager() instanceof TNTPrimed&&on(Type.TNT_DOUBLE_DAMAGE))e.setDamage(e.getDamage()*2);
        if(p==null)return;
        if(on(Type.MACE_BAN)&&weapon(p,Material.MACE)){deny(e,p,"철퇴를 사용할 수 없습니다.");return;}
        if(on(Type.MACE_DOUBLE_DAMAGE)&&weapon(p,Material.MACE))e.setDamage(e.getDamage()*2);
        if(on(Type.COUNTERATTACK)&&p.getHealth()<=18)e.setDamage(e.getDamage()*1.5);
        if(on(Type.LIFESTEAL)){double heal=Math.min(p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue(),p.getHealth()+e.getFinalDamage()*0.2);p.setHealth(heal);}
        if(on(Type.POISON_HIT)&&e.getEntity() instanceof LivingEntity l)l.addPotionEffect(new PotionEffect(PotionEffectType.POISON,100,0));
        if(on(Type.PROJECTILE_WITHER)&&e.getDamager() instanceof Projectile&&e.getEntity() instanceof LivingEntity l)l.addPotionEffect(new PotionEffect(PotionEffectType.WITHER,100,0));
        if(on(Type.SWORD_SHIELD_BREAK)&&e.getDamager() instanceof Player&&suffix(p,"_SWORD")&&e.getEntity() instanceof Player victim&&victim.isBlocking()){victim.clearActiveItem();victim.setCooldown(Material.SHIELD,100);victim.getWorld().playSound(victim.getLocation(),Sound.ITEM_SHIELD_BREAK,.8f,1f);}
        if(on(Type.AXE_CRIT_SLOW)&&e.getDamager() instanceof Player&&suffix(p,"_AXE")&&e.isCritical()&&e.getFinalDamage()>0&&e.getEntity() instanceof LivingEntity l)l.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,60,0));
        if(on(Type.KILL_INVISIBILITY))p.removePotionEffect(PotionEffectType.INVISIBILITY);
        if(on(Type.MACE_SHOCKWAVE)&&weapon(p,Material.MACE))shockwave(p,e.getEntity());
    }
    private void shockwave(Player attacker,Entity target){double radius=plugin.getConfig().getDouble("random-events.mace-shockwave-radius",4);for(Entity near:target.getNearbyEntities(radius,radius,radius))if(near instanceof Player hit&&!hit.equals(attacker)){hit.addPotionEffect(new PotionEffect(PotionEffectType.INSTANT_DAMAGE,1,1));hit.setVelocity(hit.getLocation().toVector().subtract(target.getLocation().toVector()).normalize().multiply(1.2).setY(.5));}target.setVelocity(target.getVelocity().add(target.getLocation().toVector().subtract(attacker.getLocation().toVector()).normalize().multiply(1.2).setY(.5)));}

    @EventHandler(ignoreCancelled=true) public void death(EntityDeathEvent e){Player k=e.getEntity().getKiller();if(k==null)return;if(on(Type.DOUBLE_DROPS))e.getDrops().addAll(e.getDrops().stream().map(ItemStack::clone).toList());if(on(Type.POST_KILL_SPEED))k.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,100,3));if(on(Type.KILL_INVISIBILITY))k.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY,plugin.getConfig().getInt("random-events.kill-invisibility-seconds",30)*20,0,false,false,false));if(on(Type.KILL_DROP))giveAt(e.getEntity().getLocation(),"random-events.kill-drop-items");if(on(Type.KILL_VOUCHER))giveAt(e.getEntity().getLocation(),"random-events.kill-voucher-items");}
    @EventHandler(ignoreCancelled=true) public void blockDrops(BlockDropItemEvent e){boolean placed=placedBlocks.remove(key(e.getBlockState().getBlock()));if(on(Type.DOUBLE_DROPS)&&!placed)e.getItems().forEach(i->e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation(),i.getItemStack().clone()));if(on(Type.CROP_REWARD)&&mature(e.getBlockState()))giveAt(e.getBlock().getLocation(),"random-events.crop-reward-items");}
    private boolean mature(org.bukkit.block.BlockState state){if(!(state.getBlockData() instanceof org.bukkit.block.data.Ageable a))return false;return a.getAge()==a.getMaximumAge();}
    private void giveAt(Location l,String path){plugin.getSupplyChestRegistry().rollRewards(plugin.getConfig().getStringList(path),plugin.getLogger()).forEach(i->l.getWorld().dropItemNaturally(l,i));}
    @EventHandler(ignoreCancelled=true) public void fish(PlayerFishEvent e){if(!on(Type.FISHING_FRENZY))return;if(e.getState()==PlayerFishEvent.State.FISHING){e.getHook().setMinWaitTime(20);e.getHook().setMaxWaitTime(60);}if(e.getState()==PlayerFishEvent.State.CAUGHT_FISH)giveAt(e.getPlayer().getLocation(),"random-events.fishing-reward-items");}
}
