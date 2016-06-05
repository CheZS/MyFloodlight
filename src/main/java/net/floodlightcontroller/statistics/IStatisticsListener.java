package net.floodlightcontroller.statistics;

import java.util.Map;

import net.floodlightcontroller.topology.NodePortTuple;

public interface IStatisticsListener {

	void receiveStatistics(Map<NodePortTuple, SwitchPortBandwidth> statisticsMap);
}
