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
    /** 플레이어가 놓은 블록(드롭 2배 악용 방지). 서버가 오래 켜져 있어도 메모리가 끝없이 늘지 않게 최근 20만 개만 기억. */
    private final Set<String> placedBlocks = java.util.Collections.newSetFromMap(new LinkedHashMap<String,Boolean>(){protected boolean removeEldestEntry(Map.Entry<String,Boolean> e){return size()>200_000;}});
    private Type active,last; private BukkitTask endTask,nextTask,shieldTask;private EventRoulette roulette; private boolean scheduler;
    public RandomEventManager(DeruyPlugin plugin){this.plugin=plugin;}
    // ── 보스바: 추첨 중 / 진행 중 이벤트 이름 + 남은 시간 ──
    private org.bukkit.boss.BossBar bar;private BukkitTask barTask;private long endsAt,totalMillis;
    private boolean barEnabled(){return plugin.getConfig().getBoolean("random-events.bossbar",true);}
    private void showBar(String title,org.bukkit.boss.BarColor color,double progress){
        if(!barEnabled())return;
        if(bar==null){bar=Bukkit.createBossBar(title,color,org.bukkit.boss.BarStyle.SOLID);Bukkit.getOnlinePlayers().forEach(bar::addPlayer);}
        bar.setTitle(title);bar.setColor(color);bar.setProgress(Math.clamp(progress,0,1));bar.setVisible(true);
    }
    private void hideBar(){if(barTask!=null){barTask.cancel();barTask=null;}if(bar!=null){bar.removeAll();bar=null;}}
    private void updateBar(){
        if(active==null){hideBar();return;}
        long left=Math.max(0,endsAt-System.currentTimeMillis());
        showBar("§c§l랜덤 이벤트 §f"+ChatColor.translateAlternateColorCodes('&',display(active))+" §7("+formatRemaining((left+999)/1000)+")",org.bukkit.boss.BarColor.RED,totalMillis<=0?0:(double)left/totalMillis);
    }
    /** 남은 초 → "m:ss" */
    static String formatRemaining(long seconds){seconds=Math.max(0,seconds);return seconds/60+":"+String.format(Locale.ROOT,"%02d",seconds%60);}
    public boolean barVisible(){return bar!=null;}
    org.bukkit.boss.BossBar bossBar(){return bar;}
    public Type getActive(){return active;} public boolean isSchedulerEnabled(){return scheduler;}
    public boolean isDrawing(){return roulette!=null;}
    public void startScheduler(){scheduler=true;if(active==null&&!isDrawing())scheduleNext();}
    public void stopScheduler(){scheduler=false;stopActive();if(nextTask!=null)nextTask.cancel();}
    private void scheduleNext(){if(nextTask!=null){nextTask.cancel();nextTask=null;}if(!scheduler)return;long min=Math.max(1,plugin.getConfig().getLong("random-events.interval-seconds.min",900));long max=Math.max(min,plugin.getConfig().getLong("random-events.interval-seconds.max",1800));nextTask=Bukkit.getScheduler().runTaskLater(plugin,this::startRandom,ThreadLocalRandom.current().nextLong(min,max+1)*20L);}
    public List<Type> enabledTypes(){List<Type> out=new ArrayList<>();for(String name:plugin.getConfig().getStringList("random-events.enabled-events"))try{Type t=Type.valueOf(name.toUpperCase(Locale.ROOT));if(!out.contains(t))out.add(t);}catch(IllegalArgumentException ignored){}return out;}
    public boolean startRandom(){List<Type> enabled=enabledTypes();if(enabled.isEmpty())return false;stopActive();if(nextTask!=null){nextTask.cancel();nextTask=null;}roulette=new EventRoulette(plugin,enabled,pickWinner(enabled),t->{roulette=null;start(t);});roulette.start();showBar("§6§l랜덤 이벤트 추첨 중...",org.bukkit.boss.BarColor.YELLOW,1);return true;}
    /** 가중치(random-events.weights.<이벤트>, 기본 1) 추첨. avoid-repeat면 직전 이벤트 제외(후보가 2개 이상일 때). */
    Type pickWinner(List<Type> enabled){List<Type> candidates=new ArrayList<>(enabled);if(plugin.getConfig().getBoolean("random-events.avoid-repeat",true)&&last!=null&&candidates.size()>1)candidates.remove(last);return RouletteMath.weightedPick(candidates,t->plugin.getConfig().getDouble("random-events.weights."+t.name(),1.0),ThreadLocalRandom.current().nextDouble());}
    void setLast(Type t){last=t;}
    public boolean start(Type type){stopActive();if(nextTask!=null){nextTask.cancel();nextTask=null;}active=type;last=type;long sec=Math.max(1,plugin.getConfig().getLong("random-events.duration-seconds",300));Bukkit.broadcastMessage("§c§l[랜덤 이벤트] §f"+display(type)+" §7("+sec+"초)");com.deruy.plugin.timeline.Timeline.log(plugin,"RANDOM_EVENT_START",type.name(),display(type)+" ("+sec+"초)",null);totalMillis=sec*1000L;endsAt=System.currentTimeMillis()+totalMillis;updateBar();if(bar!=null)barTask=Bukkit.getScheduler().runTaskTimer(plugin,this::updateBar,20,20);if(type==Type.POTION_BAN)Bukkit.getOnlinePlayers().forEach(p->p.getActivePotionEffects().forEach(e->p.removePotionEffect(e.getType())));applyPersistent();if(type==Type.SHIELD_BAN){enforceShields();shieldTask=Bukkit.getScheduler().runTaskTimer(plugin,this::enforceShields,1,2);}endTask=Bukkit.getScheduler().runTaskLater(plugin,()->{stopActive();scheduleNext();},sec*20L);return true;}
    public void stopActive(){hideBar();if(roulette!=null){roulette.cancel();roulette=null;}if(shieldTask!=null){shieldTask.cancel();shieldTask=null;}if(active==Type.SHIELD_BAN)for(Player p:Bukkit.getOnlinePlayers())if(p.getCooldown(Material.SHIELD)<=4)p.setCooldown(Material.SHIELD,0);if(endTask!=null){endTask.cancel();endTask=null;}if(active!=null){Bukkit.broadcastMessage("§c[랜덤 이벤트] §7이벤트가 종료되었습니다.");com.deruy.plugin.timeline.Timeline.log(plugin,"RANDOM_EVENT_END",active.name(),display(active),null);}if(active==Type.STRENGTH_III)Bukkit.getOnlinePlayers().forEach(this::removeEventStrength);active=null;}
    /** 이벤트가 준 무한 힘 III만 지운다(직접 마신 힘 포션은 유지). */
    private void removeEventStrength(Player p){PotionEffect e=p.getPotionEffect(PotionEffectType.STRENGTH);if(e!=null&&e.getAmplifier()==2&&(e.isInfinite()||e.getDuration()>1_000_000_000))p.removePotionEffect(PotionEffectType.STRENGTH);}
    private String display(Type t){return plugin.getConfig().getString("random-events.names."+t.name(),t.name());}
    private void applyPersistent(){if(active==Type.STRENGTH_III)Bukkit.getOnlinePlayers().forEach(p->p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,PotionEffect.INFINITE_DURATION,2,false,false,true)));}
    private void enforceShields(){if(active!=Type.SHIELD_BAN)return;for(Player p:Bukkit.getOnlinePlayers()){if(p.hasActiveItem()&&p.getActiveItem().getType()==Material.SHIELD)p.clearActiveItem();if(p.getCooldown(Material.SHIELD)<4)p.setCooldown(Material.SHIELD,4);}}
    private boolean on(Type t){return active==t;}
    private Player attacker(Entity e){if(e instanceof Player p)return p;if(e instanceof Projectile p&&p.getShooter() instanceof Player shooter)return shooter;return null;}
    private boolean weapon(Player p,Material m){return p.getInventory().getItemInMainHand().getType()==m;}
    private boolean suffix(Player p,String s){return p.getInventory().getItemInMainHand().getType().name().endsWith(s);}
    private String key(Block b){return b.getWorld().getUID()+":"+b.getX()+":"+b.getY()+":"+b.getZ();}
    private void deny(Cancellable e,Player p,String text){e.setCancelled(true);p.sendMessage("§c[랜덤 이벤트] "+text);}

    @EventHandler public void rouletteClick(org.bukkit.event.inventory.InventoryClickEvent e){if(e.getView().getTopInventory().getHolder() instanceof EventRoulette.Gui)e.setCancelled(true);}
    @EventHandler public void rouletteDrag(org.bukkit.event.inventory.InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof EventRoulette.Gui)e.setCancelled(true);}
    @EventHandler public void join(PlayerJoinEvent e){if(active!=Type.STRENGTH_III)removeEventStrength(e.getPlayer()); /* 나가 있는 동안 이벤트가 끝났으면 무한 힘 III 회수 (Codex 리뷰) */applyPersistent();if(bar!=null)bar.addPlayer(e.getPlayer());}
    @EventHandler(ignoreCancelled=true) public void place(BlockPlaceEvent e){placedBlocks.add(key(e.getBlockPlaced()));if(on(Type.COBWEB_BAN)&&e.getBlockPlaced().getType()==Material.COBWEB)deny(e,e.getPlayer(),"거미줄을 사용할 수 없습니다.");}
    @EventHandler(ignoreCancelled=true) public void consume(PlayerItemConsumeEvent e){if(on(Type.POTION_BAN)&&(e.getItem().getType()==Material.POTION||e.getItem().getType()==Material.SPLASH_POTION||e.getItem().getType()==Material.LINGERING_POTION))deny(e,e.getPlayer(),"포션을 사용할 수 없습니다.");}
    @EventHandler(ignoreCancelled=true) public void resurrect(EntityResurrectEvent e){if(on(Type.TOTEM_BAN)&&e.getEntity() instanceof Player p)deny(e,p,"토템을 사용할 수 없습니다.");}
    @EventHandler(ignoreCancelled=true) public void launch(ProjectileLaunchEvent e){if(!(e.getEntity().getShooter() instanceof Player p))return;if(on(Type.POTION_BAN)&&e.getEntity() instanceof ThrownPotion){deny(e,p,"포션을 사용할 수 없습니다.");return;}if(on(Type.PROJECTILE_BAN))deny(e,p,"투사체를 사용할 수 없습니다.");}
    @EventHandler(priority=EventPriority.HIGHEST) public void interact(PlayerInteractEvent e){Material item=e.getMaterial();if(on(Type.SHIELD_BAN)&&item==Material.SHIELD){e.setUseItemInHand(Event.Result.DENY);deny(e,e.getPlayer(),"방패를 사용할 수 없습니다.");e.getPlayer().clearActiveItem();e.getPlayer().setCooldown(Material.SHIELD,4);}if(on(Type.MACE_BAN)&&item==Material.MACE)deny(e,e.getPlayer(),"철퇴를 사용할 수 없습니다.");}

    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void damage(EntityDamageByEntityEvent e){
        Player p=attacker(e.getDamager());
        if(e.getDamager() instanceof TNTPrimed&&on(Type.TNT_DOUBLE_DAMAGE))multiplyFinalDamage(e,2);
        if(p==null)return;
        if(on(Type.MACE_BAN)&&weapon(p,Material.MACE)){deny(e,p,"철퇴를 사용할 수 없습니다.");return;}
        if(on(Type.MACE_DOUBLE_DAMAGE)&&e.getDamager() instanceof Player&&weapon(p,Material.MACE))multiplyFinalDamage(e,2);
        if(on(Type.COUNTERATTACK)&&p.getHealth()<=18)multiplyFinalDamage(e,1.5);
        if(on(Type.LIFESTEAL)){double heal=Math.min(p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue(),p.getHealth()+e.getFinalDamage()*0.2);p.setHealth(heal);}
        if(on(Type.POISON_HIT)&&e.getEntity() instanceof LivingEntity l)l.addPotionEffect(new PotionEffect(PotionEffectType.POISON,100,1));
        if(on(Type.PROJECTILE_WITHER)&&e.getDamager() instanceof Projectile&&e.getEntity() instanceof LivingEntity l)l.addPotionEffect(new PotionEffect(PotionEffectType.WITHER,100,1));
        if(on(Type.SWORD_SHIELD_BREAK)&&e.getDamager() instanceof Player&&suffix(p,"_SWORD")&&e.getEntity() instanceof Player victim&&victim.isBlocking()){victim.clearActiveItem();victim.setCooldown(Material.SHIELD,100);victim.getWorld().playSound(victim.getLocation(),Sound.ITEM_SHIELD_BREAK,.8f,1f);}
        if(on(Type.AXE_CRIT_SLOW)&&e.getDamager() instanceof Player&&suffix(p,"_AXE")&&e.isCritical()&&e.getFinalDamage()>0&&e.getEntity() instanceof LivingEntity l)l.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,140,2));
        if(on(Type.KILL_INVISIBILITY))p.removePotionEffect(PotionEffectType.INVISIBILITY);
        if(on(Type.MACE_SHOCKWAVE)&&weapon(p,Material.MACE))shockwave(p,e.getEntity());
    }
    /** Scale health damage after existing mitigation, without recomputing armor from a larger raw hit. */
    @SuppressWarnings("deprecation")
    static void multiplyFinalDamage(EntityDamageEvent event,double multiplier){
        double finalDamage=event.getFinalDamage();
        if(!Double.isFinite(finalDamage)||finalDamage<=0)return;
        double base=event.getDamage(EntityDamageEvent.DamageModifier.BASE);
        // This overload changes BASE only. Armor/resistance/protection/absorption amounts stay intact.
        // New final = (base + final*(multiplier-1)) + original modifiers = old final*multiplier.
        event.setDamage(EntityDamageEvent.DamageModifier.BASE,base+finalDamage*(multiplier-1));
    }
    private void shockwave(Player attacker,Entity target){double radius=plugin.getConfig().getDouble("random-events.mace-shockwave-radius",4);var roles=plugin.getRoleManager();for(Entity near:target.getNearbyEntities(radius,radius,radius))if(near instanceof Player hit&&!hit.equals(attacker)){if(roles!=null&&(!roles.isPvpAllowed(attacker)||!roles.isPvpAllowed(hit)))continue; /* PvP 금지 역할은 충격파도 막음 (Codex 리뷰) */hit.addPotionEffect(new PotionEffect(PotionEffectType.INSTANT_DAMAGE,1,1));hit.setVelocity(hit.getLocation().toVector().subtract(target.getLocation().toVector()).normalize().multiply(1.2).setY(.5));}target.setVelocity(target.getVelocity().add(target.getLocation().toVector().subtract(attacker.getLocation().toVector()).normalize().multiply(1.2).setY(.5)));}

    @EventHandler(ignoreCancelled=true) public void death(EntityDeathEvent e){Player k=e.getEntity().getKiller();if(k==null)return;if(on(Type.DOUBLE_DROPS)&&!(e.getEntity() instanceof Player))e.getDrops().addAll(e.getDrops().stream().map(ItemStack::clone).toList());if(on(Type.POST_KILL_SPEED))k.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,100,3));if(on(Type.KILL_INVISIBILITY))k.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY,plugin.getConfig().getInt("random-events.kill-invisibility-seconds",30)*20,0,false,false,false));if(on(Type.KILL_DROP))giveAt(e.getEntity().getLocation(),"random-events.kill-drop-items");if(on(Type.KILL_VOUCHER)&&e.getEntity() instanceof Player victim&&!victim.equals(k))giveAt(e.getEntity().getLocation(),"random-events.kill-voucher-items");}
    /** 피스톤으로 민 설치 블록은 옮겨진 자리로 기록 이동 (안 하면 드롭 2배로 무한 복사, Codex 리뷰) */
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void pistonExtend(org.bukkit.event.block.BlockPistonExtendEvent e){movePlaced(e.getBlocks(),e.getDirection());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void pistonRetract(org.bukkit.event.block.BlockPistonRetractEvent e){
        // 당겨지는 블록은 피스톤 쪽(피스톤이 바라보는 반대 방향)으로 한 칸
        org.bukkit.block.BlockFace facing=e.getBlock().getBlockData() instanceof org.bukkit.block.data.Directional d?d.getFacing():e.getDirection();
        movePlaced(e.getBlocks(),facing.getOppositeFace());
    }
    void movePlaced(List<Block> blocks,org.bukkit.block.BlockFace dir){
        List<String> moved=new ArrayList<>();
        for(Block b:blocks)if(placedBlocks.remove(key(b)))moved.add(key(b.getRelative(dir)));
        placedBlocks.addAll(moved);   // 다 지운 뒤 추가 (한 줄로 밀 때 서로 덮어쓰지 않게)
    }
    /** 모래·자갈처럼 떨어지는 설치 블록: 떨어지기 시작하면 엔티티로, 착지하면 새 자리로 기록 이동 */
    private final Set<UUID> fallingPlaced=new HashSet<>();
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void falling(org.bukkit.event.entity.EntityChangeBlockEvent e){
        if(!(e.getEntity() instanceof org.bukkit.entity.FallingBlock fb))return;
        if(e.getTo().isAir()){if(placedBlocks.remove(key(e.getBlock())))fallingPlaced.add(fb.getUniqueId());}
        else if(fallingPlaced.remove(fb.getUniqueId()))placedBlocks.add(key(e.getBlock()));
    }
    boolean isPlacedForTest(Block b){return placedBlocks.contains(key(b));}
    void markPlacedForTest(Block b){placedBlocks.add(key(b));}
    @EventHandler(ignoreCancelled=true) public void blockDrops(BlockDropItemEvent e){boolean placed=placedBlocks.remove(key(e.getBlockState().getBlock()));if(on(Type.DOUBLE_DROPS)&&!placed)e.getItems().forEach(i->e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation(),i.getItemStack().clone()));if(on(Type.CROP_REWARD)&&mature(e.getBlockState()))giveAt(e.getBlock().getLocation(),"random-events.crop-reward-items");}
    private boolean mature(org.bukkit.block.BlockState state){if(!(state.getBlockData() instanceof org.bukkit.block.data.Ageable a))return false;return a.getAge()==a.getMaximumAge();}
    private void giveAt(Location l,String path){plugin.getSupplyChestRegistry().rollRewards(plugin.getConfig().getStringList(path),plugin.getLogger()).forEach(i->l.getWorld().dropItemNaturally(l,i));}
    @EventHandler(ignoreCancelled=true) public void fish(PlayerFishEvent e){if(!on(Type.FISHING_FRENZY))return;if(e.getState()==PlayerFishEvent.State.FISHING){e.getHook().setMinWaitTime(20);e.getHook().setMaxWaitTime(60);}if(e.getState()==PlayerFishEvent.State.CAUGHT_FISH)giveAt(e.getPlayer().getLocation(),"random-events.fishing-reward-items");}
}
