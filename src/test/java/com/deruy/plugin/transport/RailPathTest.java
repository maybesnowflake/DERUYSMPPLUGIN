package com.deruy.plugin.transport;
import org.bukkit.block.data.Rail;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RailPathTest {
 private static RailPath.Cell c(int x,int y,int z){return new RailPath.Cell(x,y,z);}
 @Test void straightPathHasConstantSpeedAndCorrectEndpoints(){var rails=Map.of(c(0,64,0),Rail.Shape.EAST_WEST,c(1,64,0),Rail.Shape.EAST_WEST,c(2,64,0),Rail.Shape.EAST_WEST);var p=RailPath.find(c(0,64,0),c(2,64,0),rails::get,10).path();assertEquals(2,p.length(),1e-8);assertEquals(.5,p.sample(0).point().x(),1e-8);assertEquals(1.5,p.sample(.5).point().x(),1e-8);assertEquals(2.5,p.sample(1).point().x(),1e-8);}
 @Test void curveFollowsRailRatherThanCuttingCorner(){var rails=Map.of(c(0,64,0),Rail.Shape.NORTH_SOUTH,c(0,64,1),Rail.Shape.NORTH_EAST,c(1,64,1),Rail.Shape.EAST_WEST);var p=RailPath.find(c(0,64,0),c(1,64,1),rails::get,10);assertEquals(3,p.cells().size());var midpoint=p.path().sample(.5).point();assertEquals(.6464466094,midpoint.x(),1e-6);assertEquals(1.3535533906,midpoint.z(),1e-6);}
 @Test void slopeConnectsDifferentElevationsInBothDirections(){var rails=Map.of(c(0,64,0),Rail.Shape.EAST_WEST,c(1,64,0),Rail.Shape.ASCENDING_EAST,c(2,65,0),Rail.Shape.EAST_WEST);var forward=RailPath.find(c(0,64,0),c(2,65,0),rails::get,10).path();var reverse=RailPath.find(c(2,65,0),c(0,64,0),rails::get,10).path();assertEquals(65.0625,forward.sample(1).point().y(),1e-8);assertEquals(64.0625,reverse.sample(1).point().y(),1e-8);assertEquals(forward.length(),reverse.length(),1e-8);}
 @Test void nearbyButWrongOrientationIsNotConnected(){var rails=Map.of(c(0,64,0),Rail.Shape.EAST_WEST,c(1,64,0),Rail.Shape.NORTH_SOUTH);assertThrows(IllegalArgumentException.class,()->RailPath.find(c(0,64,0),c(1,64,0),rails::get,10));}
 @Test void searchLimitStopsLongRoute(){Map<RailPath.Cell,Rail.Shape> rails=new HashMap<>();for(int x=0;x<20;x++)rails.put(c(x,64,0),Rail.Shape.EAST_WEST);assertThrows(IllegalArgumentException.class,()->RailPath.find(c(0,64,0),c(19,64,0),rails::get,10));}
 @Test void everyShapeHasTwoPorts(){for(var shape:Rail.Shape.values())assertEquals(2,RailPath.ports(shape).size());}
}
