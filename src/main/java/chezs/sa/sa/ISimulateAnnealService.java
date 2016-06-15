package chezs.sa.sa;

import java.util.List;
import java.util.Map;

import org.projectfloodlight.openflow.protocol.OFStatsReply;
import org.projectfloodlight.openflow.types.DatapathId;

import net.floodlightcontroller.core.module.IFloodlightService;

public interface ISimulateAnnealService extends IFloodlightService {
	public void receiveActiveFlow(Map<DatapathId, List<OFStatsReply>> model);
}
