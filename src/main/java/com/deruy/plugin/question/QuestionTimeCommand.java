package com.deruy.plugin.question;
import org.bukkit.command.*; import java.util.*;
public final class QuestionTimeCommand implements TabExecutor {
    private final QuestionTimeManager manager;
    public QuestionTimeCommand(QuestionTimeManager manager){this.manager=manager;}
    public boolean onCommand(CommandSender s,Command c,String l,String[] a){
        String sub=a.length==0?"status":a[0].toLowerCase(Locale.ROOT);
        boolean view=sub.equals("status"); String perm=view?"deruy.question.view":"deruy.question.admin";
        if(!s.hasPermission(perm)){s.sendMessage("§c권한이 없습니다.");return true;}
        switch(sub){case "start"->manager.start();case "stop"->manager.stop();case "force"->{if(!manager.askRandom())s.sendMessage("§c출제할 수 없습니다.");}case "status"->s.sendMessage("§6질문타임: "+(manager.isEnabled()?"§a예약 활성화":"§7비활성화")+(manager.isQuestionActive()?" §e(문제 진행 중)":""));default->s.sendMessage("§e/questiontime start|stop|force|status");} return true;
    }
    public List<String> onTabComplete(CommandSender s,Command c,String l,String[]a){return a.length==1?List.of("status","start","stop","force").stream().filter(x->x.startsWith(a[0].toLowerCase())).toList():List.of();}
}
