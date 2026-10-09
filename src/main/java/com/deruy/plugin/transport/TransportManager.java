package com.deruy.plugin.transport;

import com.deruy.plugin.DeruyPlugin;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.scheduler.BukkitTask;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** One convoy per server. All Bukkit state is owned by the main server thread. */
public final class TransportManager implements Listener {
    private final DeruyPlugin plugin;
    private final File file;
    private YamlConfiguration routes;
    private WagonModel model;
    private final TransportLoot loot;
    private final TransportRaid raid;
    private boolean railMode;
    public static final String POINT_ROUTE="points";
    private Map<RailPath.Cell,org.bukkit.block.data.Rail.Shape> railShapes=Map.of();
    private final List<Display> displays=new ArrayList<>();
    private final Set<Chunk> tickets=new HashSet<>();
    private BukkitTask task;
    private String runningRoute;
    private List<Leg> legs=List.of();
    private int legIndex, elapsed, dwell;
    private Location current;
    private record Leg(World world,RoutePath path,int ticks,int waitTicks,int destination){}
    public TransportManager(DeruyPlugin plugin) throws IOException {
        this.plugin=plugin;file=new File(plugin.getDataFolder(),"transport-routes.yml");
        routes=new YamlConfiguration();
        if(file.exists())try{routes.load(file);}catch(Exception e){throw new IOException("노선 파일 오류",e);}
        model=new WagonModel(plugin);loot=new TransportLoot(plugin);raid=new TransportRaid(plugin,this,loot);
        for(World w:Bukkit.getWorlds())for(Chunk c:w.getLoadedChunks())cleanup(c);
    }
    public void registerGameListeners(){Bukkit.getPluginManager().registerEvents(loot,plugin);Bukkit.getPluginManager().registerEvents(raid,plugin);}
    public Location currentLocation(){return current==null?null:current.clone();}
    public void setRailPoint(String id,String which,Location feet) throws IOException {
        editable();String b=base(id);if(!routes.contains(b))throw new IllegalArgumentException("먼저 /transport create "+id);
        RailPath.Cell cell=RailPath.atPlayer(feet);routes.set(b+".rail."+which,RailPath.saved(feet.getWorld(),cell));save();
    }
    public void setPoint(int n,Location feet) throws IOException {
        editable();if(!routes.contains(base(POINT_ROUTE)))create(POINT_ROUTE);setRegion(POINT_ROUTE,n,"stop",feet);
    }
    public void pointTiming(int from,int to,double seconds) throws IOException {
        editable();var sec=routes.getConfigurationSection(base(POINT_ROUTE)+".regions");
        if(sec==null)throw new IllegalArgumentException("먼저 /transport point <번호> 로 지점을 찍으세요.");
        List<Integer> ids=sec.getKeys(false).stream().map(Integer::parseInt).sorted().toList();int index=ids.indexOf(from);
        if(index<0||index+1>=ids.size()||ids.get(index+1)!=to)throw new IllegalArgumentException("숫자 순서상 이웃한 두 지점을 입력하세요. 예: /transport speed 1 2 30");
        timing(POINT_ROUTE,from,seconds,0);
    }
    private void startRail(String id,String b) throws IOException {
        Location a=routes.getLocation(b+".rail.start"),z=routes.getLocation(b+".rail.end");
        if(a==null||z==null||a.getWorld()==null||!a.getWorld().equals(z.getWorld()))throw new IllegalArgumentException("같은 월드의 레일 위에서 railstart / railend를 설정하세요.");
        var rails=RailPath.inWorld(a.getWorld());var plan=RailPath.find(new RailPath.Cell(a.getBlockX(),a.getBlockY(),a.getBlockZ()),new RailPath.Cell(z.getBlockX(),z.getBlockY(),z.getBlockZ()),rails,4096);
        double speed=raid.load();Map<RailPath.Cell,org.bukkit.block.data.Rail.Shape> shapes=new HashMap<>();for(var cell:plan.cells())shapes.put(cell,rails.shape(cell));
        stop();railMode=true;railShapes=Map.copyOf(shapes);legs=List.of(new Leg(a.getWorld(),plan.path(),Math.max(20,(int)Math.ceil(plan.path().length()/speed*20)),0,0));legIndex=0;elapsed=0;dwell=0;
        try{Location at=location(legs.getFirst(),plan.path().sample(0));spawn(at);runningRoute=id;raid.begin(at);task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,2,2);}catch(RuntimeException e){stop();throw e;}
        Bukkit.broadcastMessage("§c♥ [하트 운송전] §e"+id+" 열차가 출발했습니다! 허용된 그룹은 열차를 공격할 수 있습니다.");
    }
    private boolean tracksIntact(Location at){
        var rails=RailPath.inWorld(at.getWorld());for(int y=at.getBlockY();y>=at.getBlockY()-1;y--){var c=new RailPath.Cell(at.getBlockX(),y,at.getBlockZ());var shape=railShapes.get(c);if(shape!=null)return shape==rails.shape(c);}return false;
    }
    public boolean running(){return runningRoute!=null;}
    public String status(){return running()?runningRoute+(railMode?" → 레일 도착점"+raid.status():" → 지점 "+legs.get(legIndex).destination+raid.status())+(dwell>0?" (정차 중)":" (운송 중)"):displays.isEmpty()?"대기 중":"모델 미리보기 중";}
    public Set<String> routes(){var s=routes.getConfigurationSection("routes");return s==null?Set.of():s.getKeys(false);}
    private String base(String id){if(!id.matches("[a-zA-Z0-9_-]{1,40}"))throw new IllegalArgumentException("노선 이름은 영문·숫자·_·- 1~40자입니다.");return "routes."+id;}
    private void editable(){if(running())throw new IllegalArgumentException("운송을 stop 한 뒤 노선을 수정하세요.");}
    private void save() throws IOException {
        Files.createDirectories(file.toPath().getParent());Path tmp=file.toPath().resolveSibling("transport-routes.yml.tmp");routes.save(tmp.toFile());
        try{Files.move(tmp,file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,file.toPath(),StandardCopyOption.REPLACE_EXISTING);}
    }
    public void create(String id) throws IOException {editable();String b=base(id);if(routes.contains(b))throw new IllegalArgumentException("이미 있는 노선입니다.");routes.set(b+".regions",new LinkedHashMap<>());save();}
    private String region(String id,int n){String b=base(id);if(!routes.contains(b))throw new IllegalArgumentException("먼저 /transport create "+id);if(n<1||n>100)throw new IllegalArgumentException("구역 번호: 1~100");return b+".regions."+n;}
    public void setRegion(String id,int n,String which,Location loc) throws IOException {
        editable();if(!Set.of("pos1","pos2","stop").contains(which))throw new IllegalArgumentException("pos1 / pos2 / stop 중 선택하세요.");
        routes.set(region(id,n)+"."+which,loc.clone());save();
    }
    public void timing(String id,int from,double seconds,double wait) throws IOException {
        editable();region(id,from);if(!Double.isFinite(seconds)||seconds<1||seconds>86400||!Double.isFinite(wait)||wait<0||wait>3600)throw new IllegalArgumentException("이동 1~86400초, 정차 0~3600초");
        routes.set(base(id)+".segments."+from+".seconds",seconds);routes.set(base(id)+".segments."+from+".wait-seconds",wait);save();
    }
    public void waypoint(String id,int from,Location loc,boolean clear) throws IOException {
        editable();region(id,from);String p=base(id)+".segments."+from+".via";
        List<Location> list=new ArrayList<>();if(!clear){for(Object o:routes.getList(p,List.of()))if(o instanceof Location l)list.add(l);if(list.size()>=200)throw new IllegalArgumentException("구간당 경유점은 최대 200개입니다.");list.add(loc.clone());}
        routes.set(p,list);save();
    }
    public List<String> describe(String id){String b=base(id);if(!routes.contains(b))throw new IllegalArgumentException("없는 노선입니다.");List<String> out=new ArrayList<>();if(routes.contains(b+".rail")){out.add("레일 출발: "+routes.getLocation(b+".rail.start"));out.add("레일 도착: "+routes.getLocation(b+".rail.end"));return out;}var sec=routes.getConfigurationSection(b+".regions");if(sec!=null)for(String k:sec.getKeys(false)){Location l=routes.getLocation(b+".regions."+k+".stop");out.add("구역 "+k+": "+(l==null?"정차점 미설정":l.getWorld().getName()+" "+String.format(Locale.ROOT,"%.1f %.1f %.1f",l.getX(),l.getY(),l.getZ()))+" / 다음 구간 "+routes.getDouble(b+".segments."+k+".seconds",60)+"초");}return out;}
    private Location stopPoint(String b,int n){String p=b+".regions."+n;Location a=routes.getLocation(p+".pos1"),z=routes.getLocation(p+".pos2"),s=routes.getLocation(p+".stop");
        if(b.equals(base(POINT_ROUTE))&&s!=null&&s.getWorld()!=null)return s;
        if(a==null||z==null||s==null||s.getWorld()==null||!Objects.equals(a.getWorld(),s.getWorld())||!Objects.equals(z.getWorld(),s.getWorld()))throw new IllegalArgumentException("구역 "+n+"의 pos1·pos2·stop을 같은 월드에 설정하세요.");
        if(s.getBlockX()<Math.min(a.getBlockX(),z.getBlockX())||s.getBlockX()>Math.max(a.getBlockX(),z.getBlockX())||s.getBlockY()<Math.min(a.getBlockY(),z.getBlockY())||s.getBlockY()>Math.max(a.getBlockY(),z.getBlockY())||s.getBlockZ()<Math.min(a.getBlockZ(),z.getBlockZ())||s.getBlockZ()>Math.max(a.getBlockZ(),z.getBlockZ()))throw new IllegalArgumentException("구역 "+n+"의 stop이 구역 밖입니다.");return s;
    }
    private static RoutePath.Point point(Location l){return new RoutePath.Point(l.getX(),l.getY(),l.getZ());}
    public void start(String id) throws IOException {
        if(!running()&&routes.contains(base(id)+".rail")){startRail(id,base(id));return;}
        if(running())throw new IllegalArgumentException("이미 운송 중입니다.");String b=base(id);var sec=routes.getConfigurationSection(b+".regions");if(sec==null)throw new IllegalArgumentException("없는 노선입니다.");
        List<Integer> ids=sec.getKeys(false).stream().map(Integer::parseInt).sorted().toList();if(ids.size()<2)throw new IllegalArgumentException("구역이 2개 이상 필요합니다.");
        List<Leg> built=new ArrayList<>();
        for(int i=0;i<ids.size()-1;i++){
            int n=ids.get(i);Location a=stopPoint(b,n),z=stopPoint(b,ids.get(i+1));if(!a.getWorld().equals(z.getWorld()))throw new IllegalArgumentException("차원 간 운송은 지원하지 않습니다.");
            List<RoutePath.Point> points=new ArrayList<>();points.add(point(a));
            for(Object o:routes.getList(b+".segments."+n+".via",List.of())){if(!(o instanceof Location l)||!a.getWorld().equals(l.getWorld()))throw new IllegalArgumentException("경유점 월드 오류");points.add(point(l));}points.add(point(z));
            double seconds=routes.getDouble(b+".segments."+n+".seconds",60),wait=routes.getDouble(b+".segments."+n+".wait-seconds",0);
            if(!Double.isFinite(seconds)||seconds<1||seconds>86400||!Double.isFinite(wait)||wait<0||wait>3600)throw new IllegalArgumentException("구간 시간 설정 오류");
            double length=0;for(int j=1;j<points.size();j++)length+=points.get(j).distance(points.get(j-1));
            if(!id.equals(POINT_ROUTE)&&length/seconds>16)throw new IllegalArgumentException("구간 "+n+" 속도가 16블록/초를 넘습니다. 시간을 늘리세요.");
            built.add(new Leg(a.getWorld(),new RoutePath(points),(int)Math.round(seconds*20),(int)Math.round(wait*20),ids.get(i+1)));
        }
        boolean pointRaid=id.equals(POINT_ROUTE);if(pointRaid)raid.load();
        stop();legs=List.copyOf(built);legIndex=0;elapsed=0;dwell=0;
        Leg first=legs.getFirst();var s=first.path.sample(0);Location at=location(first,s);spawn(at);runningRoute=id;if(pointRaid){try{raid.begin(at);}catch(RuntimeException e){stop();throw e;}}
        task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,2,2);
        Bukkit.broadcastMessage("§c♥ [하트 운송전] §e"+id+" 노선 운송이 시작되었습니다.");
    }
    private Location location(Leg l,RoutePath.Sample s){return new Location(l.world,s.point().x(),s.point().y(),s.point().z(),s.yaw(),0);}
    private void tick(){
        try {
            if(displays.isEmpty()||displays.stream().anyMatch(d->!d.isValid())||(POINT_ROUTE.equals(runningRoute)&&!raid.valid())){stop();Bukkit.broadcastMessage("§c[하트 운송전] 운송체가 없어 운송을 중단했습니다.");return;}
            if(dwell>0){dwell=Math.max(0,dwell-2);if(dwell>0)return;if(legIndex==legs.size()-1){arrive();return;}legIndex++;elapsed=0;}
            Leg leg=legs.get(legIndex);elapsed=Math.min(leg.ticks,elapsed+2);Location next=location(leg,leg.path.sample((double)elapsed/leg.ticks));
            if(railMode&&(!raid.valid()||!tracksIntact(next))){stop();Bukkit.broadcastMessage("§c[하트 운송전] 레일 또는 공격 판정이 없어 운송을 중단했습니다.");return;}move(next);
            if(railMode&&elapsed==leg.ticks){arrive();return;}
            if(elapsed==leg.ticks){Bukkit.broadcastMessage("§c♥ [하트 운송전] §e구역 "+leg.destination+"에 도착했습니다.");dwell=leg.waitTicks;if(dwell==0){if(legIndex==legs.size()-1)arrive();else{legIndex++;elapsed=0;}}}
        }catch(Exception e){plugin.getLogger().severe("운송 중단: "+e.getMessage());stop();}
    }
    private void arrive(){raid.clear();railMode=false;railShapes=Map.of();Bukkit.broadcastMessage("§c♥ [하트 운송전] §a운송이 완료되었습니다.");runningRoute=null;if(task!=null){task.cancel();task=null;} // Keep the model until remove/next preview.
    }
    public void preview(Location where){if(running())throw new IllegalArgumentException("운송을 먼저 stop 하세요.");stop();Location l=where.clone();l.setPitch(0);spawn(l);}
    private void spawn(Location at){try{hold(at);displays.addAll(model.spawn(at));current=at.clone();}catch(RuntimeException e){stop();throw e;}}
    private void move(Location at){hold(at);for(Display d:displays)if(!d.teleport(at))throw new IllegalStateException("디스플레이 이동이 차단되었습니다.");current=at.clone();raid.move(at);}
    private void hold(Location at){Set<Chunk> needed=new HashSet<>();int cx=at.getBlockX()>>4,cz=at.getBlockZ()>>4;for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){Chunk c=at.getWorld().getChunkAt(cx+dx,cz+dz);needed.add(c);if(!tickets.contains(c)&&c.addPluginChunkTicket(plugin))tickets.add(c);}
        for(Chunk c:new HashSet<>(tickets))if(!needed.contains(c)){c.removePluginChunkTicket(plugin);tickets.remove(c);}
    }
    public void reloadModel() throws IOException {if(running())throw new IllegalArgumentException("운송을 먼저 stop 하세요.");WagonModel candidate=new WagonModel(plugin);stop();model=candidate;}
    public void stop(){raid.clear();railMode=false;railShapes=Map.of();if(task!=null){task.cancel();task=null;}runningRoute=null;displays.forEach(Entity::remove);displays.clear();for(Chunk c:tickets)c.removePluginChunkTicket(plugin);tickets.clear();current=null;}
    public int entityCount(){return displays.size();}
    private void cleanup(Chunk c){for(Entity e:c.getEntities())if(e.getScoreboardTags().contains(WagonModel.TAG)&&!raid.owns(e.getUniqueId())&&displays.stream().noneMatch(d->d.getUniqueId().equals(e.getUniqueId())))e.remove();}
    @EventHandler public void onChunkLoad(ChunkLoadEvent event){cleanup(event.getChunk());}
}
