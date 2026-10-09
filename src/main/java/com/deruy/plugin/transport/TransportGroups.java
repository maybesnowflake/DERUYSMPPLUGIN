package com.deruy.plugin.transport;
import net.luckperms.api.LuckPermsProvider;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.util.*;
final class TransportGroups {
 private TransportGroups() {}
 static List<String> validate(List<String> groups) {
  if(!Bukkit.getPluginManager().isPluginEnabled("LuckPerms"))throw new IllegalArgumentException("운송전에는 LuckPerms가 필요합니다.");
  if(groups.isEmpty())throw new IllegalArgumentException("transport-game.yml에 허용 그룹을 지정하세요.");
  var api=LuckPermsProvider.get();List<String> out=new ArrayList<>();
  for(String input:groups){String s=input.trim().toLowerCase(Locale.ROOT);if(!s.matches("[a-z0-9_-]{1,64}")||api.getGroupManager().getGroup(s)==null)throw new IllegalArgumentException("없는 LuckPerms 그룹: "+s);if(!out.contains(s))out.add(s);}
  return List.copyOf(out);
 }
 static boolean allows(Player player,Collection<String> groups,boolean inherited) {
  if(!Bukkit.getPluginManager().isPluginEnabled("LuckPerms"))return false;
  try {
   var api=LuckPermsProvider.get();var user=api.getUserManager().getUser(player.getUniqueId());if(user==null)return false;
   var query=api.getContextManager().getQueryOptions(player);
   Collection<String> names=inherited?user.getInheritedGroups(query).stream().map(g->g.getName().toLowerCase(Locale.ROOT)).toList():user.getNodes(net.luckperms.api.node.NodeType.INHERITANCE).stream().filter(n->!n.hasExpired()&&n.getValue()&&n.getContexts().isSatisfiedBy(query.context())).map(n->n.getGroupName().toLowerCase(Locale.ROOT)).toList();
   return names.stream().anyMatch(groups::contains);
  }catch(IllegalStateException e){return false;}
 }
}
