package com.deruy.plugin.transport;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.*;

public final class TransportCommand implements TabExecutor {
    private final TransportManager manager;
    public TransportCommand(TransportManager manager){this.manager=manager;}
    public boolean onCommand(CommandSender s,Command c,String label,String[] a){
        String sub=a.length==0?"help":a[0].toLowerCase(Locale.ROOT);
        boolean view=Set.of("help","status","list","info").contains(sub);
        if(!s.hasPermission(view?"deruy.transport.view":"deruy.transport.admin")){s.sendMessage("§c권한이 없습니다.");return true;}
        try{
            switch(sub){
                case "preview"->{manager.preview(player(s).getLocation());s.sendMessage("§a화물칸 소환 완료 ("+manager.entityCount()+"개 요소). /transport remove 로 제거");}
                case "remove","stop"->{manager.stop();s.sendMessage("§7운송체와 이동 작업을 제거했습니다.");}
                case "point"->{require(a,2);manager.setPoint(Integer.parseInt(a[1]),player(s).getLocation());s.sendMessage("§a지점 "+a[1]+"을 현재 발 위치로 저장했습니다.");}
                case "speed"->{require(a,4);manager.pointTiming(Integer.parseInt(a[1]),Integer.parseInt(a[2]),Double.parseDouble(a[3]));s.sendMessage("§a지점 "+a[1]+" → "+a[2]+" 이동 시간을 "+a[3]+"초로 저장했습니다.");}
                case "create"->{require(a,2);manager.create(a[1]);s.sendMessage("§a노선을 만들었습니다.");}
                case "railstart","railend"->{require(a,2);manager.setRailPoint(a[1],sub.equals("railstart")?"start":"end",player(s).getLocation());s.sendMessage("§a발 아래 레일을 저장했습니다.");}
                case "region"->{require(a,4);manager.setRegion(a[1],Integer.parseInt(a[2]),a[3].toLowerCase(Locale.ROOT),player(s).getLocation());s.sendMessage("§a현재 위치를 저장했습니다.");}
                case "time"->{require(a,4);manager.timing(a[1],Integer.parseInt(a[2]),Double.parseDouble(a[3]),a.length>4?Double.parseDouble(a[4]):0);s.sendMessage("§a구간 이동·도착 후 정차 시간을 저장했습니다.");}
                case "via"->{require(a,3);manager.waypoint(a[1],Integer.parseInt(a[2]),player(s).getLocation(),false);s.sendMessage("§a현재 위치를 경유점으로 추가했습니다.");}
                case "clearvia"->{require(a,3);manager.waypoint(a[1],Integer.parseInt(a[2]),null,true);s.sendMessage("§a경유점을 지웠습니다.");}
                case "start"->{manager.start(a.length>1?a[1]:TransportManager.POINT_ROUTE);}
                case "reload"->{manager.reloadModel();s.sendMessage("§atransport-model.yml을 다시 읽었습니다.");}
                case "status"->s.sendMessage("§6[운송전] §f"+manager.status());
                case "list"->s.sendMessage("§6노선: §f"+String.join(", ",manager.routes()));
                case "info"->{require(a,2);manager.describe(a[1]).forEach(line->s.sendMessage("§e"+line));}
                default->help(s);
            }
        }catch(NumberFormatException e){s.sendMessage("§c구역 번호와 시간은 숫자로 입력하세요.");}
        catch(IllegalArgumentException e){s.sendMessage("§c"+e.getMessage());}
        catch(Exception e){s.sendMessage("§c처리 실패: "+e.getMessage());}
        return true;
    }
    private static Player player(CommandSender s){if(s instanceof Player p)return p;throw new IllegalArgumentException("현재 위치가 필요한 명령어입니다. 게임에서 실행하세요.");}
    private static void require(String[] a,int n){if(a.length<n)throw new IllegalArgumentException("인수가 부족합니다. /transport help 를 확인하세요.");}
    private static void help(CommandSender s){s.sendMessage("§6/transport preview | remove | status | list | info <노선>");if(!s.hasPermission("deruy.transport.admin"))return;s.sendMessage("§e/transport point <번호> (현재 발 위치)");s.sendMessage("§e/transport speed <출발번호> <도착번호> <이동초>");s.sendMessage("§e/transport start (지점 노선 시작)");s.sendMessage("§e/transport create <노선>");s.sendMessage("§e/transport railstart|railend <노선> (레일 위에서 실행)");s.sendMessage("§e/transport region <노선> <구역번호> pos1|pos2|stop");s.sendMessage("§e/transport time <노선> <출발구역> <이동초> [도착후정차초]");s.sendMessage("§e/transport via|clearvia <노선> <출발구역>");s.sendMessage("§e/transport start <노선> | stop | reload");}
    public List<String> onTabComplete(CommandSender s,Command c,String alias,String[] a){
        boolean admin=s.hasPermission("deruy.transport.admin");if(!admin&&!s.hasPermission("deruy.transport.view"))return List.of();List<String> options=List.of();
        if(a.length==1)options=admin?List.of("help","preview","remove","point","speed","create","railstart","railend","region","time","via","clearvia","start","stop","status","list","info","reload"):List.of("help","status","list","info");
        else if(a.length==2&&(admin||a[0].equalsIgnoreCase("info")))options=new ArrayList<>(manager.routes());
        else if(admin&&a.length==4&&a[0].equalsIgnoreCase("region"))options=List.of("pos1","pos2","stop");
        String prefix=a[a.length-1].toLowerCase(Locale.ROOT);return options.stream().filter(v->v.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
