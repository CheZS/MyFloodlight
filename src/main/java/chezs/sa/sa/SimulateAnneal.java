package chezs.sa.sa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;

import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.OFPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.routing.Link;
import net.floodlightcontroller.topology.ITopologyService;

public class SimulateAnneal implements IOFMessageListener, IFloodlightModule {
	protected ITopologyService topologyService;
	protected IFloodlightProviderService floodlightProvider;
	protected static Logger logger;
	
	protected int k;				// k-fat tree topology 
	protected int N;				// iteration number in every temperature
	protected int T;				// cooling number
	protected double coolingRate;	// cooling param
	protected double t0;			// start temperature
	
	protected int coreSwitchNum;
	
	protected int bestT;
	
	/*
	 * each flow: [inH, inE, inA, core, outA, outE, outH]
	 * energy: overflowing bandwidth in all flows
	 * neighbors: random choose 2 flows and then switch their "c"
	 */
	protected Flow[] currFlows;
	protected Flow[] bestFlows;
	protected double bestEvaluation;
	
	protected int iterator;
	
//	protected int[][] Ghh;
//	protected int GhhEvaluation;
//	protected int[] bestGh;
//	protected int[][] tempGhh;
//	protected int tempEvaluation;
	
	protected Random random;
	
	
	private class SrcDst {
		public DatapathId src;
		public DatapathId dst;
		public SrcDst(DatapathId src, DatapathId dst) {
			this.src = src;
			this.dst = dst;
		}
	}
	protected Set<SrcDst> todoFlowSet;		// flow cache
	
	public void sa() {
		initSa();
		
		double currEvaluation = evaluate(currFlows);
		
		Flow[] neighbor;
		double currTemperature = t0;
		
		for (; T > 0; T--) {
			neighbor = generateNeighbor();
			double neighborEvaluation = evaluate(neighbor);
			
			double delta = neighborEvaluation - currEvaluation;
			
			boolean isAccept = Math.exp(-delta / currTemperature) > random.nextDouble();
			if (delta < 0 || isAccept) {
				currFlows = copyState(neighbor);
				currEvaluation = neighborEvaluation;
			}
			if (currEvaluation < bestEvaluation) {
				bestEvaluation = currEvaluation;
				bestFlows = copyState(currFlows);
				bestT = T;
			}
			currTemperature *= coolingRate;
		}
	}
	
	private void initSa() {
		t0 = 250;
		coolingRate = 0.98;
		T = 400;
		bestEvaluation = Double.MAX_VALUE;
		bestT = -1;
		random = new Random(new Date().getTime());
		
		k = 4;
		
		currFlows = new Flow[todoFlowSet.size()];
		
		// random generate currFlows
		Map<DatapathId, Set<Link>> linkMap = topologyService.getAllLinks();
		int count = 0;
		for (SrcDst sd : todoFlowSet) {
			// get inEdge
			Set<Link> srcLinkSet = linkMap.get(sd.src);
			Link srcLink = (Link) srcLinkSet.toArray()[0];
			DatapathId inEdge = srcLink.getDst();
			// get outEdge
			Set<Link> dstLinkSet = linkMap.get(sd.dst);
			Link dstLink = (Link) dstLinkSet.toArray()[0];
			DatapathId outEdge = dstLink.getDst();
			// random get aggregation port => get inAggregation & outAggregation
			int aggregationPort = random.nextInt(k/2) + 1;
			DatapathId inAggregation = null;
			for (Link l : linkMap.get(inEdge)) {
				if (l.getSrcPort().equals(OFPort.of(aggregationPort))) {
					inAggregation = l.getDst();
					break;
				}
			}
			// get outAggregation
			DatapathId outAggregation = null;
			for (Link l : linkMap.get(outEdge)) {
				if (l.getSrcPort().equals(OFPort.of(aggregationPort))) {
					outAggregation = l.getDst();
					break;
				}
			}
			// random get core port => get core
			int corePort = random.nextInt(k/2) + 1;
			DatapathId core = null;
			for (Link l : linkMap.get(inAggregation)) {
				if (l.getSrcPort().equals(OFPort.of(corePort))) {
					core = l.getDst();
					break;
				}
			}
			// generate new Flow
			currFlows[count] = new Flow(sd.src, inEdge, inAggregation, core, outAggregation, outEdge, sd.dst, 900);
		}
	}
	
	private double evaluate(Flow[] state) {
		Map<DatapathId, Set<Link>> linkMap = topologyService.getAllLinks();
		Map<Link, Double> costMap = new HashMap<Link, Double>();
		
		DatapathId[] dpidArray;
		
		for (Flow flow : state) {
			dpidArray = flow.getDpidArray();
			for (int i = 0; i < dpidArray.length - 1; i++) {
				DatapathId srcDpid = dpidArray[i];
				DatapathId dstDpid = dpidArray[i + 1];
				
				Set<Link> linkSet = linkMap.get(srcDpid);
				Iterator<Link> iter = linkSet.iterator();
				
				while (iter.hasNext()) {
					Link link = iter.next();
					
					if (link.getDst().equals(dstDpid)) {
						Double cost;
						if (costMap.containsKey(link)) {
							cost = costMap.get(link) + flow.requestBandwidthMB;
						} else {
							cost = flow.requestBandwidthMB;
						}
						costMap.put(link, cost);
						break;
					}
				}
			}
		}
		
		double res = 0;
		for (Entry<Link, Double> entry : costMap.entrySet()) {
			Double value = entry.getValue();
			if (value > 1000) {
				// max bandwidth is 1000MB
				res += value - 1000;
			}
		}
		return res;
	}
	
	private Flow[] generateNeighbor() {
		Flow[] neighbor = copyState(currFlows);
		int flowNum = neighbor.length;
		int flow0 = random.nextInt(flowNum);
		int flow1 = random.nextInt(flowNum - 1);
		if (flow1 < flow0) {
			flow1++;
		}
		
		DatapathId c0 = neighbor[flow0].core;
		DatapathId c1 = neighbor[flow1].core;
		Map<DatapathId, Set<Link>> linkMap = topologyService.getAllLinks();
		
		// get a0 port
		DatapathId inA0 = currFlows[flow0].inAggregation;
		DatapathId inE0 = currFlows[flow0].inEdge;
		OFPort a0Port = null;
		for (Link l : linkMap.get(inE0)) {
			if (l.getDst().equals(inA0)) {
				a0Port = l.getSrcPort();
				break;
			}
		}
		
		// get a1 port
		DatapathId inA1 = currFlows[flow1].inAggregation;
		DatapathId inE1 = currFlows[flow1].inEdge;
		OFPort a1Port = null;
		for (Link l : linkMap.get(inE1)) {
			if (l.getDst().equals(inA1)) {
				a1Port = l.getSrcPort();
				break;
			}
		}
		
		// get new input aggregation 0 & output aggregation 0
		DatapathId inANew0 = null;
		for (Link l : linkMap.get(inE0)) {
			if (l.getSrcPort().equals(a1Port)) {
				inANew0 = l.getDst();
				break;
			}
		}
		DatapathId outANew0 = null;
		DatapathId outE0 = currFlows[flow0].outEdge;
		for (Link l : linkMap.get(outE0)) {
			if (l.getSrcPort().equals(a1Port)) {
				outANew0 = l.getDst();
				break;
			}
		}
		
		// get new input aggregation 1 & output aggregation 1
		DatapathId inANew1 = null;
		for (Link l : linkMap.get(inE1)) {
			if (l.getSrcPort().equals(a0Port)) {
				inANew1 = l.getDst();
				break;
			}
		}
		DatapathId outANew1 = null;
		DatapathId outE1 = currFlows[flow1].outEdge;
		for (Link l : linkMap.get(outE1)) {
			if (l.getSrcPort().equals(a0Port)) {
				outANew1 = l.getDst();
			}
		}
		
		// switch 2 flows
		neighbor[flow0].core = c1;
		neighbor[flow0].inAggregation = inANew0;
		neighbor[flow0].outAggregation = outANew0;
		neighbor[flow1].core = c0;
		neighbor[flow1].inAggregation = inANew1;
		neighbor[flow1].outAggregation = outANew1;
		
		return neighbor;
	}
	
	private Flow[] copyState(Flow[] state) {
		Flow[] res = new Flow[state.length];
		for (int i = 0; i < state.length; i++) {
			res[i] = new Flow(state[i]);
		}
		return res;
	}
	
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		// TODO Auto-generated method stub
		Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IFloodlightProviderService.class);
		l.add(ITopologyService.class);
		return l;
	}

	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		this.floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
		this.topologyService = context.getServiceImpl(ITopologyService.class);
		this.todoFlowSet= new HashSet<SrcDst>();
		logger = LoggerFactory.getLogger(SimulateAnneal.class);
	}

	@Override
	public void startUp(FloodlightModuleContext context)
			throws FloodlightModuleException {
		// TODO Auto-generated method stub
		floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
	}

	@Override
	public String getName() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		// TODO Auto-generated method stub
		return false;
	}

	/**
	 * get flow by listen IOFMessage
	 */
	@Override
	public net.floodlightcontroller.core.IListener.Command receive(IOFSwitch sw,
			OFMessage msg, FloodlightContext cntx) {
		// store flow to todoFlowSet
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		DatapathId srcDpid = DatapathId.of(eth.getSourceMACAddress());
		DatapathId dstDpid = DatapathId.of(eth.getDestinationMACAddress());
		todoFlowSet.add(new SrcDst(srcDpid, dstDpid));
		return Command.CONTINUE;
	}

}
