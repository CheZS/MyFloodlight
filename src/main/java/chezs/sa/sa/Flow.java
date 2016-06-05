package chezs.sa.sa;

import org.projectfloodlight.openflow.types.DatapathId;

public class Flow {
	public DatapathId inHost;
	public DatapathId inEdge;
	public DatapathId inAggregation;
	public DatapathId core;
	public DatapathId outAggregation;
	public DatapathId outEdge;
	public DatapathId outHost;
	public double requestBandwidthMB;

	private Flow() {
	}

	public Flow(Flow flow) {
		new Flow();
		inHost = flow.inHost;
		inEdge = flow.inEdge;
		inAggregation = flow.inAggregation;
		core = flow.core;
		outAggregation = flow.outAggregation;
		outEdge = flow.outEdge;
		outHost = flow.outHost;
		requestBandwidthMB = flow.requestBandwidthMB;
	}

	public Flow(DatapathId inHost, DatapathId inEdge, DatapathId inAggregation,
			DatapathId core, DatapathId outAggregation, DatapathId outEdge,
			DatapathId outHost, double requestBandwidthMB) {
		this.inHost = inHost;
		this.inEdge = inEdge;
		this.inAggregation = inAggregation;
		this.core = core;
		this.outAggregation = outAggregation;
		this.outEdge = outEdge;
		this.outHost = outHost;
		this.requestBandwidthMB = requestBandwidthMB;
	}

	public DatapathId[] getDpidArray() {
		DatapathId[] dpidArray = new DatapathId[7];
		dpidArray[0] = inHost;
		dpidArray[1] = inEdge;
		dpidArray[2] = inAggregation;
		dpidArray[3] = core;
		dpidArray[4] = outAggregation;
		dpidArray[5] = outEdge;
		dpidArray[6] = outHost;
		return dpidArray;
	}
}
