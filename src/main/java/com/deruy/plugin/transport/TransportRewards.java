package com.deruy.plugin.transport;
import com.deruy.plugin.DeruyPlugin;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Durable shared rewards. Overflow remains in storage rather than dropping outside its group restriction. */
public final class TransportRewards implements Listener {
 private static final NamespacedKey KEY=new NamespacedKey("deruy","transport_reward_chest");
 private final DeruyPlugin plugin;private final File file;private final Map<String,Batch> batches=new LinkedHashMap<>();private final BukkitTask timer;private Consumer<String> done=id->{};
 private static final class Batch {String id;Location at,chest;long expires;List<String> groups;boolean inherited;List<ItemStack> items;}
 private static final class View implements InventoryHolder {final String id;final Inventory inv;View(String id){this.id=id;inv=Bukkit.createInventory(this,54,"§6호위 보상 상자");}public Inventory getInventory(){return inv;}}
 public TransportRewards(DeruyPlugin plugin) throws IOException {this.plugin=plugin;file=new File(plugin.getDataFolder(),"transport-rewards.yml");if(file.exists()){YamlConfiguration c=new YamlConfiguration();try{c.load(file);}catch(Exception e){throw new IOException("보상 저장 파일 오류",e);}var section=c.getConfigurationSection("rewards");if(section!=null)for(String id:section.getKeys(false)){String p="rewards."+id;Batch b=new Batch();b.id=id;b.at=c.getLocation(p+".location");b.chest=c.getLocation(p+".chest");b.expires=c.getLong(p+".expires");b.groups=c.getStringList(p+".groups");b.inherited=c.getBoolean(p+".inherited");b.items=new ArrayList<>();for(Object item:c.getList(p+".items",List.of()))if(item instanceof ItemStack i)b.items.add(i.clone());if(b.at==null||b.at.getWorld()==null||b.groups.isEmpty())throw new IOException("보상 "+id+" 위치/그룹 오류");batches.put(id,b);}}
  timer=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,20,20);
 }
 public void onDone(Consumer<String> callback){done=callback;}
 public String create(Location at,List<ItemStack> items,List<String> groups,boolean inherited,int seconds) throws IOException {Batch b=new Batch();b.id=UUID.randomUUID().toString();b.at=at.clone();b.items=compact(items);b.groups=List.copyOf(groups);b.inherited=inherited;b.expires=System.currentTimeMillis()+seconds*1000L;batches.put(b.id,b);try{save();}catch(IOException e){batches.remove(b.id);throw e;}return b.id;}
 public static List<ItemStack> compact(List<ItemStack> input){List<ItemStack> out=new ArrayList<>();for(ItemStack source:input){int left=source.getAmount();for(ItemStack stack:out)if(stack.isSimilar(source)){int n=Math.min(left,stack.getMaxStackSize()-stack.getAmount());stack.setAmount(stack.getAmount()+n);left-=n;}while(left>0){var one=source.clone();int n=Math.min(left,one.getMaxStackSize());one.setAmount(n);out.add(one);left-=n;}}return out;}
 private boolean allowed(Player p,Batch b){return p.getGameMode()!=GameMode.SPECTATOR&&TransportGroups.allows(p,b.groups,b.inherited);}
 public void claim(String id,Player p){Batch b=batches.get(id);if(b==null)return;if(!allowed(p,b)){p.sendMessage("§c호위 그룹만 보상을 가져갈 수 있습니다.");return;}List<ItemStack> old=b.items.stream().map(ItemStack::clone).toList();ItemStack[] inventory=Arrays.stream(p.getInventory().getStorageContents()).map(i->i==null?null:i.clone()).toArray(ItemStack[]::new);List<ItemStack> left=new ArrayList<>();for(var i:b.items)left.addAll(p.getInventory().addItem(i.clone()).values());b.items=left;try{save();}catch(IOException e){b.items=new ArrayList<>(old);p.getInventory().setStorageContents(inventory);p.sendMessage("§c보상 저장에 실패했습니다. 다시 시도하세요.");return;}p.sendMessage(left.isEmpty()?"§a호위 보상을 모두 수령했습니다.":"§e남은 보상이 있습니다. 인벤토리를 비우고 다시 우클릭하세요.");refresh(id);if(left.isEmpty())finish(b);}
 private void finish(Batch b){if(b.chest!=null&&idAt(b.chest.getBlock()).equals(b.id))b.chest.getBlock().setType(Material.AIR,false);batches.remove(b.id);try{save();}catch(IOException e){plugin.getLogger().severe("완료 보상 저장 실패: "+e.getMessage());}for(Player p:Bukkit.getOnlinePlayers())if(currentHolder(p) instanceof View v&&v.id.equals(b.id))p.closeInventory();done.accept(b.id);}
 private void tick(){for(Batch b:new ArrayList<>(batches.values())){if(b.chest!=null&&!idAt(b.chest.getBlock()).equals(b.id))b.chest=null;if(b.chest==null&&System.currentTimeMillis()>=b.expires){Location spot=chestSpot(b.at);if(spot==null)continue;Block block=spot.getBlock();block.setType(Material.CHEST,false);Chest chest=(Chest)block.getState();chest.getPersistentDataContainer().set(KEY,PersistentDataType.STRING,b.id);chest.setCustomName("호위 보상 상자");chest.update(true,false);b.chest=spot;try{save();}catch(IOException e){b.chest=null;block.setType(Material.AIR,false);continue;}Bukkit.broadcastMessage("§6[하트 운송전] §e미수령 보상을 호위 전용 상자에 보관했습니다. "+spot.getBlockX()+", "+spot.getBlockY()+", "+spot.getBlockZ());done.accept(b.id);}}}
 private Location chestSpot(Location at){World w=at.getWorld();for(int radius=2;radius<=6;radius++)for(int dy=0;dy<=2;dy++)for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++){if(Math.max(Math.abs(x),Math.abs(z))!=radius)continue;Block b=w.getBlockAt(at.getBlockX()+x,at.getBlockY()+dy,at.getBlockZ()+z);if(!b.getType().isAir()||!b.getRelative(BlockFace.DOWN).getType().isSolid())continue;boolean near=false;for(BlockFace f:List.of(BlockFace.NORTH,BlockFace.SOUTH,BlockFace.EAST,BlockFace.WEST))if(b.getRelative(f).getType()==Material.CHEST)near=true;if(!near)return b.getLocation();}return null;}
 private void save() throws IOException {YamlConfiguration c=new YamlConfiguration();for(Batch b:batches.values()){String p="rewards."+b.id;c.set(p+".location",b.at);c.set(p+".chest",b.chest);c.set(p+".expires",b.expires);c.set(p+".groups",b.groups);c.set(p+".inherited",b.inherited);c.set(p+".items",b.items);}Files.createDirectories(file.toPath().getParent());Path tmp=file.toPath().resolveSibling("transport-rewards.yml.tmp");c.save(tmp.toFile());try{Files.move(tmp,file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,file.toPath(),StandardCopyOption.REPLACE_EXISTING);}}
 public void shutdown(){timer.cancel();try{save();}catch(IOException e){plugin.getLogger().severe("호위 보상 저장 실패: "+e.getMessage());}}
 private String idAt(Block b){return b.getState() instanceof Chest c?c.getPersistentDataContainer().getOrDefault(KEY,PersistentDataType.STRING,""):"";}
 private void open(Player p,Batch b){View v=new View(b.id);fill(v,b);p.openInventory(v.inv);}
 private void fill(View v,Batch b){v.inv.clear();for(int i=0;i<Math.min(54,b.items.size());i++)v.inv.setItem(i,b.items.get(i).clone());}
 private Object currentHolder(Player p){var top=p.getOpenInventory().getTopInventory();return top==null?null:top.getHolder();}
 private void refresh(String id){Batch b=batches.get(id);if(b==null)return;for(Player p:Bukkit.getOnlinePlayers())if(currentHolder(p) instanceof View v&&v.id.equals(id))fill(v,b);}
 @EventHandler(priority=EventPriority.HIGHEST) public void interact(PlayerInteractEvent e){if(e.getAction()!=Action.RIGHT_CLICK_BLOCK||e.getClickedBlock()==null)return;String id=idAt(e.getClickedBlock());if(id.isEmpty())return;e.setCancelled(true);Batch b=batches.get(id);if(b!=null&&allowed(e.getPlayer(),b))open(e.getPlayer(),b);else e.getPlayer().sendMessage("§c호위 그룹만 이 상자를 열 수 있습니다.");}
 @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent e){if(!(e.getView().getTopInventory().getHolder() instanceof View v))return;e.setCancelled(true);if(!(e.getWhoClicked() instanceof Player p))return;Batch b=batches.get(v.id);if(b==null||!allowed(p,b)){p.closeInventory();return;}if(e.getRawSlot()<0||e.getRawSlot()>=Math.min(54,b.items.size()))return;int slot=e.getRawSlot();var old=b.items.stream().map(ItemStack::clone).toList();var item=b.items.get(slot);var before=item.clone();var inventory=Arrays.stream(p.getInventory().getStorageContents()).map(i->i==null?null:i.clone()).toArray(ItemStack[]::new);var left=p.getInventory().addItem(item.clone());b.items.remove(slot);b.items.addAll(slot,left.values());try{save();}catch(IOException x){b.items=new ArrayList<>(old);p.getInventory().setStorageContents(inventory);return;}refresh(b.id);if(b.items.isEmpty())finish(b);}
 @EventHandler public void drag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof View)e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST) public void inventory(InventoryOpenEvent e){if(e.getInventory().getHolder() instanceof Chest c&&!idAt(c.getBlock()).isEmpty())e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST) public void breakChest(BlockBreakEvent e){if(!idAt(e.getBlock()).isEmpty())e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST) public void explode(EntityExplodeEvent e){e.blockList().removeIf(b->!idAt(b).isEmpty());}
 @EventHandler(priority=EventPriority.HIGHEST) public void blockExplode(BlockExplodeEvent e){e.blockList().removeIf(b->!idAt(b).isEmpty());}
 @EventHandler(priority=EventPriority.HIGHEST) public void piston(BlockPistonExtendEvent e){if(e.getBlocks().stream().anyMatch(b->!idAt(b).isEmpty()))e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST) public void pistonPull(BlockPistonRetractEvent e){if(e.getBlocks().stream().anyMatch(b->!idAt(b).isEmpty()))e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST) public void place(BlockPlaceEvent e){if(e.getBlock().getType()!=Material.CHEST)return;for(BlockFace f:List.of(BlockFace.NORTH,BlockFace.SOUTH,BlockFace.EAST,BlockFace.WEST))if(!idAt(e.getBlock().getRelative(f)).isEmpty()){e.setCancelled(true);return;}}
}
