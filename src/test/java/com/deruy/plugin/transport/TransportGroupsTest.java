package com.deruy.plugin.transport;
import net.luckperms.api.*;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class TransportGroupsTest {
 LuckPerms api;User user;Player player;QueryOptions query;MockedStatic<LuckPermsProvider> provider;
 @BeforeEach void setup(){MockBukkit.mock();MockBukkit.createMockPlugin("LuckPerms");api=mock(LuckPerms.class,RETURNS_DEEP_STUBS);user=mock(User.class);player=mock(Player.class);query=mock(QueryOptions.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.isOp()).thenReturn(true);when(api.getUserManager().getUser(player.getUniqueId())).thenReturn(user);when(api.getContextManager().getQueryOptions(player)).thenReturn(query);when(query.context()).thenReturn(mock(ImmutableContextSet.class));provider=mockStatic(LuckPermsProvider.class);provider.when(LuckPermsProvider::get).thenReturn(api);}
 @AfterEach void cleanup(){provider.close();MockBukkit.unmock();}
 InheritanceNode node(String group,boolean matches,boolean expired){var n=mock(InheritanceNode.class);when(n.getGroupName()).thenReturn(group);when(n.getValue()).thenReturn(true);when(n.hasExpired()).thenReturn(expired);var context=mock(ImmutableContextSet.class);when(n.getContexts()).thenReturn(context);when(context.isSatisfiedBy(query.context())).thenReturn(matches);return n;}
 @Test void opWithoutGroupIsDenied(){when(user.getNodes(NodeType.INHERITANCE)).thenReturn(List.of());assertFalse(TransportGroups.allows(player,List.of("raiders"),false));}
 @Test void allowedDirectGroupCanPickUp(){var n=node("raiders",true,false);when(user.getNodes(NodeType.INHERITANCE)).thenReturn(List.of(n));assertTrue(TransportGroups.allows(player,List.of("raiders"),false));}
 @Test void expiredOrWrongWorldGroupIsDenied(){var expired=node("raiders",true,true);var context=node("raiders",false,false);when(user.getNodes(NodeType.INHERITANCE)).thenReturn(List.of(expired,context));assertFalse(TransportGroups.allows(player,List.of("raiders"),false));}
 @Test void inheritedGroupRequiresSetting(){var child=node("child",true,false);when(user.getNodes(NodeType.INHERITANCE)).thenReturn(List.of(child));var group=mock(Group.class);when(group.getName()).thenReturn("raiders");when(user.getInheritedGroups(query)).thenReturn(List.of(group));assertFalse(TransportGroups.allows(player,List.of("raiders"),false));assertTrue(TransportGroups.allows(player,List.of("raiders"),true));}
 @Test void uncachedUserIsDenied(){when(api.getUserManager().getUser(player.getUniqueId())).thenReturn(null);assertFalse(TransportGroups.allows(player,List.of("raiders"),false));}
 @Test void unknownConfiguredGroupStopsStart(){when(api.getGroupManager().getGroup("missing")).thenReturn(null);assertThrows(IllegalArgumentException.class,()->TransportGroups.validate(List.of("missing")));}
}
