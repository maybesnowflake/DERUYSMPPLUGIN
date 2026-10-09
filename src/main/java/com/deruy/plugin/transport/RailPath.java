package com.deruy.plugin.transport;
import org.bukkit.*;
import org.bukkit.block.data.Rail;
import java.util.*;
/** Mutually connected rail graph; samples curved and sloped rail geometry. */
public final class RailPath {
 public record Cell(int x,int y,int z){}
 public record Port(int x,int z,int rise){}
 @FunctionalInterface public interface Rails {Rail.Shape shape(Cell cell);}
 public record Result(RoutePath path,List<Cell> cells){}
 private RailPath(){}
 public static List<Port> ports(Rail.Shape s) {
  Port n=new Port(0,-1,0),south=new Port(0,1,0),e=new Port(1,0,0),w=new Port(-1,0,0);
  return switch(s){case NORTH_SOUTH->List.of(n,south);case EAST_WEST->List.of(e,w);case ASCENDING_EAST->List.of(new Port(1,0,1),w);case ASCENDING_WEST->List.of(e,new Port(-1,0,1));case ASCENDING_NORTH->List.of(new Port(0,-1,1),south);case ASCENDING_SOUTH->List.of(n,new Port(0,1,1));case NORTH_EAST->List.of(n,e);case NORTH_WEST->List.of(n,w);case SOUTH_EAST->List.of(south,e);case SOUTH_WEST->List.of(south,w);};
 }
 private static Port connection(Cell a,Cell b,Rails rails) {
  Rail.Shape sa=rails.shape(a),sb=rails.shape(b);if(sa==null||sb==null)return null;
  for(Port p:ports(sa))if(a.x+p.x==b.x&&a.z+p.z==b.z)for(Port q:ports(sb))if(q.x==-p.x&&q.z==-p.z&&a.y+p.rise==b.y+q.rise)return p;return null;
 }
 public static Result find(Cell start,Cell end,Rails rails,int limit) {
  if(start.equals(end))throw new IllegalArgumentException("출발·도착 레일이 같습니다.");if(rails.shape(start)==null||rails.shape(end)==null)throw new IllegalArgumentException("출발·도착점은 레일이어야 합니다.");
  Map<Cell,Cell> prev=new HashMap<>();ArrayDeque<Cell> queue=new ArrayDeque<>();queue.add(start);prev.put(start,start);
  while(!queue.isEmpty()&&!prev.containsKey(end)) {Cell a=queue.remove();for(Port p:ports(rails.shape(a)))for(int y:new int[]{a.y+p.rise,a.y+p.rise-1}){Cell b=new Cell(a.x+p.x,y,a.z+p.z);if(!prev.containsKey(b)&&connection(a,b,rails)!=null){if(prev.size()>=limit)throw new IllegalArgumentException("레일 탐색 한도 "+limit+"개를 넘었습니다.");prev.put(b,a);queue.add(b);}}}
  if(!prev.containsKey(end))throw new IllegalArgumentException("서로 연결된 레일 경로를 찾지 못했습니다.");
  List<Cell> cells=new ArrayList<>();for(Cell c=end;;c=prev.get(c)){cells.add(c);if(c.equals(start))break;}Collections.reverse(cells);List<RoutePath.Point> points=new ArrayList<>();
  for(int i=0;i<cells.size();i++) {Cell c=cells.get(i);List<Port> pp=ports(rails.shape(c));Port exit=i+1<cells.size()?connection(c,cells.get(i+1),rails):null,entry=i>0?connection(c,cells.get(i-1),rails):null;
   if(entry==null)entry=pp.get(0).equals(exit)?pp.get(1):pp.get(0);if(exit==null)exit=pp.get(0).equals(entry)?pp.get(1):pp.get(0);double lo=i==0?.5:0,hi=i==cells.size()-1?.5:1;
   for(int j=0;j<=8;j++)points.add(onRail(c,entry,exit,lo+(hi-lo)*j/8));
  }
  return new Result(new RoutePath(points),List.copyOf(cells));
 }
 static RoutePath.Point onRail(Cell c,Port entry,Port exit,double t) {
  double x,z,y;if(entry.x*exit.x+entry.z*exit.z==0){double cx=.5*(entry.x+exit.x),cz=.5*(entry.z+exit.z),a=Math.atan2(.5*entry.z-cz,.5*entry.x-cx),b=Math.atan2(.5*exit.z-cz,.5*exit.x-cx),d=Math.atan2(Math.sin(b-a),Math.cos(b-a));x=cx+.5*Math.cos(a+d*t);z=cz+.5*Math.sin(a+d*t);y=0;}else{x=.5*(entry.x+(exit.x-entry.x)*t);z=.5*(entry.z+(exit.z-entry.z)*t);y=entry.rise+(exit.rise-entry.rise)*t;}
  return new RoutePath.Point(c.x+.5+x,c.y+.0625+y,c.z+.5+z);
 }
 public static Cell atPlayer(Location feet) {for(int dy:new int[]{0,-1}){var b=feet.getWorld().getBlockAt(feet.getBlockX(),feet.getBlockY()+dy,feet.getBlockZ());if(b.getBlockData() instanceof Rail)return new Cell(b.getX(),b.getY(),b.getZ());}throw new IllegalArgumentException("레일 위에 서서 실행하세요.");}
 public static Rails inWorld(World world){return c->{var d=world.getBlockAt(c.x,c.y,c.z).getBlockData();return d instanceof Rail r?r.getShape():null;};}
 public static Location saved(World w,Cell c){return new Location(w,c.x,c.y,c.z);}
}
