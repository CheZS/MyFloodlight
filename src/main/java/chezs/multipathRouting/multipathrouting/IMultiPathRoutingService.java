package chezs.multipathRouting.multipathrouting;

import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.OFPort;

import chezs.multipathRouting.multipathrouting.types.MultiRoute;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.routing.Route;

public interface IMultiPathRoutingService extends IFloodlightService  {
    public void modifyLinkCost(DatapathId srcDpid,DatapathId dstDpid,double cost);
    public Route getRoute(DatapathId srcDpid,OFPort srcPort,DatapathId dstDpid,OFPort dstPort);
	public MultiRoute getMultiRoute(DatapathId srcDpid, DatapathId dstDpid);
}
