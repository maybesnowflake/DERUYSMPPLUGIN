package com.deruy.plugin.transport;
import java.util.*;

/** Pure arc-length path math: all bends share the selected total segment duration. */
public final class RoutePath {
    public record Point(double x,double y,double z) {
        public Point { if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IllegalArgumentException("Non-finite point"); }
        double distance(Point p){return Math.sqrt(Math.pow(x-p.x,2)+Math.pow(y-p.y,2)+Math.pow(z-p.z,2));}
        Point lerp(Point p,double t){return new Point(x+(p.x-x)*t,y+(p.y-y)*t,z+(p.z-z)*t);}
    }
    public record Sample(Point point,float yaw){}
    private final List<Point> points; private final double[] lengths; private final double total;
    public RoutePath(List<Point> points){
        if(points.size()<2)throw new IllegalArgumentException("경로에 두 점 이상이 필요합니다.");
        this.points=List.copyOf(points);lengths=new double[points.size()-1];double sum=0;
        for(int i=0;i<lengths.length;i++){lengths[i]=points.get(i).distance(points.get(i+1));sum+=lengths[i];}
        if(sum<.001)throw new IllegalArgumentException("출발점과 도착 경로가 동일합니다.");total=sum;
    }
    public double length(){return total;}
    public Sample sample(double progress){
        if(!Double.isFinite(progress))throw new IllegalArgumentException("progress");
        double remaining=Math.clamp(progress,0,1)*total;
        for(int i=0;i<lengths.length;i++){
            if(lengths[i]<.000001)continue;
            if(remaining<=lengths[i]||i==lengths.length-1){Point a=points.get(i),b=points.get(i+1);return new Sample(a.lerp(b,Math.clamp(remaining/lengths[i],0,1)),(float)Math.toDegrees(Math.atan2(-(b.x-a.x),b.z-a.z)));}
            remaining-=lengths[i];
        }
        // Repeated endpoint: retain the heading of the final nonzero edge.
        for(int i=lengths.length-1;i>=0;i--)if(lengths[i]>.000001){Point a=points.get(i),b=points.get(i+1);return new Sample(points.getLast(),(float)Math.toDegrees(Math.atan2(-(b.x-a.x),b.z-a.z)));}
        throw new IllegalStateException();
    }
}
