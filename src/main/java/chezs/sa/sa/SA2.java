package chezs.sa.sa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;

import org.projectfloodlight.openflow.protocol.OFFactories;
import org.projectfloodlight.openflow.protocol.OFFlowAdd.Builder;
import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.OFFlowStatsEntry;
import org.projectfloodlight.openflow.protocol.OFFlowStatsReply;
import org.projectfloodlight.openflow.protocol.OFMatchV3;
import org.projectfloodlight.openflow.protocol.OFStatsReply;
import org.projectfloodlight.openflow.protocol.OFVersion;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.U64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.util.AppCookie;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.routing.Link;
import net.floodlightcontroller.routing.Route;
import net.floodlightcontroller.routing.RouteId;
import net.floodlightcontroller.staticflowentry.IStaticFlowEntryPusherService;
import net.floodlightcontroller.topology.ITopologyService;
import net.floodlightcontroller.topology.NodePortTuple;

public class SA2 implements IFloodlightModule, ISimulateAnnealService {

	protected static ITopologyService topologyService;
	protected static IDeviceService deviceService;
	protected static IOFSwitchService switchService;
	protected static IStaticFlowEntryPusherService staticFlowEntryPusherService;
	protected static Logger logger;

	protected int k; // k-fat tree topology
	protected int N; // iteration number in every temperature
	protected int T; // cooling number
	protected double coolingRate; // cooling param
	protected double t0; // start temperature

	protected int coreSwitchNum;

	protected int bestT;

	protected Map<DatapathId, DatapathId> currState;
	protected Map<SrcDst, Route> currRoute;
	protected Map<DatapathId, DatapathId> bestState;
	protected Map<SrcDst, Route> bestRoute;
	protected double bestEvaluation;

	protected int iterator;

	protected Random random;
	protected static Set<DatapathId> hostSet;
	protected static Set<DatapathId> edgeSwitchSet;
	protected static Set<DatapathId> aggregationSwitchSet;
	protected static Set<DatapathId> coreSwitchSet;

	private class SrcDst {
		public DatapathId src;
		public DatapathId dst;
		public MacAddress srcMac;
		public MacAddress dstMac;
		public double demandMb;

		public SrcDst(DatapathId src, DatapathId dst, MacAddress srcMac, MacAddress dstMac, double demandMb) {
			this.src = src;
			this.dst = dst;
			this.srcMac = srcMac;
			this.dstMac = dstMac;
			this.demandMb = demandMb;
		}
	}

	protected static Set<SrcDst> todoFlowSet; // flow cache
	protected static Map<DatapathId, Map<DatapathId, Link>> srcDstLinkMap;

	private class Pod {
		public Set<DatapathId> hosts;
		public Set<DatapathId> edges;
		public Set<DatapathId> aggregations;

		public Pod() {
			this.hosts = new HashSet<DatapathId>();
			this.edges = new HashSet<DatapathId>();
			this.aggregations = new HashSet<DatapathId>();
		}
	}

	protected static Set<Pod> podSet;

	public void sa() {
		initSA();

		double currEvaluation = evaluate(currState, currRoute);

		Map<DatapathId, DatapathId> neighbor;
		Map<SrcDst, Route> neighborRoute = null;
		double currTemperature = t0;

		for (; T > 0; T--) {
			neighbor = generateNeighbor();
			double neighborEvaluation = evaluate(neighbor, neighborRoute);

			double delta = neighborEvaluation - currEvaluation;

			boolean isAccept = Math.exp(-delta / currTemperature) > random
					.nextDouble();
			if (delta < 0 || isAccept) {
				currState = copyState(neighbor);
				currRoute = copyRoute(neighborRoute);
				currEvaluation = neighborEvaluation;
			}
			if (currEvaluation < bestEvaluation) {
				bestEvaluation = currEvaluation;
				bestState = copyState(currState);
				bestRoute = copyRoute(currRoute);
				bestT = T;
			}
			currTemperature *= coolingRate;
		}
		
		// push flow to switches
		for (Entry<SrcDst, Route> entry : bestRoute.entrySet()) {
			SrcDst sd = entry.getKey();
			Route route = entry.getValue();
			U64 cookie = AppCookie.makeCookie(6666, 0);
			for (int index = route.getPath().size() - 1; index > 0; index -= 2) {
				DatapathId swDpid = route.getPath().get(index).getNodeId();
				IOFSwitch sw = switchService.getSwitch(swDpid);
				OFPort outport = route.getPath().get(index).getPortId();
				OFPort inport = route.getPath().get(index - 1).getPortId();
				
				Match.Builder mb = OFFactories.getFactory(OFVersion.OF_13).buildMatch();
				mb.setExact(MatchField.IN_PORT, inport)
				.setExact(MatchField.ETH_SRC, sd.srcMac)
				.setExact(MatchField.ETH_DST, sd.dstMac);
				Match match = mb.build();
				
				OFActionOutput.Builder aob = OFFactories.getFactory(OFVersion.OF_13).actions().buildOutput();
				List<OFAction> actions = new ArrayList<OFAction>();
				aob.setPort(outport);
				aob.setMaxLen(Integer.MAX_VALUE);
				actions.add(aob.build());
				
				Builder b = OFFactories.getFactory(OFVersion.OF_13).buildFlowAdd();
				b.setMatch(match)
				.setIdleTimeout(5)
				.setHardTimeout(0)
				.setBufferId(OFBufferId.NO_BUFFER)
				.setCookie(cookie)
				.setOutPort(outport)
				.setPriority(0);
				b.setInstructions(Collections.singletonList((OFInstruction) sw
						.getOFFactory().instructions().applyActions(actions)));
				OFFlowMod fm = b.build();
				
				staticFlowEntryPusherService.addFlow(sd.src.toString() + sd.dst.toString(), fm, swDpid);
			}
		}
	}

	private void initSA() {
		t0 = 250;
		coolingRate = 0.98;
		T = 400;
		bestEvaluation = Double.MAX_VALUE;
		bestT = -1;
		random = new Random(new Date().getTime());

		k = 4;

		currState = new HashMap<DatapathId, DatapathId>();

		Map<DatapathId, Set<Link>> linkMap = topologyService.getAllLinks();
		for (Entry<DatapathId, Set<Link>> entry : linkMap.entrySet()) {
			for (Link link : entry.getValue()) {
				if (srcDstLinkMap.containsKey(link.getSrc())) {
					srcDstLinkMap.get(link.getSrc()).put(link.getDst(), link);
				} else {
					Map<DatapathId, Link> newMap = new HashMap<DatapathId, Link>();
					newMap.put(link.getDst(), link);
					srcDstLinkMap.put(link.getSrc(), newMap);
				}
			}
		}
		// update host set
		for (Entry<DatapathId, Set<Link>> entry : linkMap.entrySet()) {
			if (entry.getValue().size() == 1) {
				hostSet.add(entry.getKey());
			}
		}
		// update edge switch set
		for (DatapathId host : hostSet) {
			Set<Link> linkSet = linkMap.get(host);
			if (linkSet.isEmpty()) {
				continue;
			}
			Iterator<Link> iter = linkSet.iterator();
			edgeSwitchSet.add(iter.next().getDst());
		}
		// update aggregation switch set
		for (DatapathId edge : edgeSwitchSet) {
			Iterator<Link> iter = linkMap.get(edge).iterator();
			while (iter.hasNext()) {
				Link link = iter.next();
				if (!hostSet.contains(link.getDst())) {
					aggregationSwitchSet.add(link.getDst());
				}
			}
		}
		// update core switch set
		for (DatapathId aggr : aggregationSwitchSet) {
			Iterator<Link> iter = linkMap.get(aggr).iterator();
			while (iter.hasNext()) {
				Link link = iter.next();
				if (!edgeSwitchSet.contains(link.getDst())) {
					coreSwitchSet.add(link.getDst());
				}
			}
		}
		// update pod set
		for (DatapathId aggregation : aggregationSwitchSet) {
			Pod belongPod = null;
			for (Entry<DatapathId, Link> entry : srcDstLinkMap.get(aggregation)
					.entrySet()) {
				DatapathId dpid = entry.getKey();
				if (edgeSwitchSet.contains(dpid)) {
					for (Pod pod : podSet) {
						if (pod.edges.contains(dpid)) {
							pod.aggregations.add(aggregation);
							belongPod = pod;
							break;
						}
					}
				}
			}
			if (belongPod == null) {
				belongPod = new Pod();
				belongPod.aggregations.add(aggregation);
				DatapathId edge = null;
				for (Entry<DatapathId, Link> entry : srcDstLinkMap
						.get(aggregation).entrySet()) {
					if (edgeSwitchSet.contains(entry.getKey())) {
						edge = entry.getKey();
						break;
					}
				}
				belongPod.edges.add(edge);
				for (Entry<DatapathId, Link> entry2 : srcDstLinkMap.get(edge)
						.entrySet()) {
					DatapathId dpid2 = entry2.getKey();
					if (hostSet.contains(dpid2)) {
						belongPod.hosts.add(dpid2);
					}
				}
				podSet.add(belongPod);
			}
		}
		// random map the host to a core switch
		DatapathId[] coreSwitchArr = (DatapathId[]) coreSwitchSet.toArray();
		coreSwitchNum = coreSwitchArr.length;
		for (DatapathId host : hostSet) {
			currState.put(host, coreSwitchArr[random.nextInt(coreSwitchNum)]);
		}
	}

	private double evaluate(Map<DatapathId, DatapathId> state, Map<SrcDst, Route> routes) {
		Map<Link, Double> costMap = new HashMap<Link, Double>();
		routes = new HashMap<SrcDst, Route>();
		// assign a unique path to every flow
		Map<DatapathId, Set<Link>> linkMap = topologyService.getAllLinks();
		for (SrcDst sd : todoFlowSet) {
			Set<Link> srcLinks = linkMap.get(sd.src);
			Set<Link> dstLinks = linkMap.get(sd.dst);
			if (srcLinks.iterator().next().getDst()
					.equals(dstLinks.iterator().next().getDst())) {
				// same edge switch
				// src - edge - dst
				if (costMap.containsKey(srcLinks.iterator().next())) {
					costMap.put(srcLinks.iterator().next(), sd.demandMb
							+ costMap.get(srcLinks.iterator().next()));
				} else {
					costMap.put(srcLinks.iterator().next(), sd.demandMb);
				}
				DatapathId edge = srcLinks.iterator().next().getDst();
				Link srcToEdge = srcDstLinkMap.get(sd.src).get(edge);
				Link edgeToDst = srcDstLinkMap.get(edge).get(sd.dst);
				if (costMap.containsKey(edgeToDst)) {
					costMap.put(edgeToDst,
							sd.demandMb + costMap.get(edgeToDst));
				} else {
					costMap.put(edgeToDst, sd.demandMb);
				}
				// add route
				List<NodePortTuple> nptList = new ArrayList<NodePortTuple>();
				nptList.add(new NodePortTuple(sd.src, srcToEdge.getSrcPort()));
				nptList.add(new NodePortTuple(edge, srcToEdge.getDstPort()));
				nptList.add(new NodePortTuple(edge, edgeToDst.getSrcPort()));
				nptList.add(new NodePortTuple(sd.dst, edgeToDst.getDstPort()));
				routes.put(sd, new Route(new RouteId(sd.src, sd.dst), nptList));
				continue;
			}
			boolean isSamePod = false;
			for (Pod pod : podSet) {
				if (pod.hosts.contains(sd.src) && pod.hosts.contains(sd.dst)) {
					// same pod
					// src - srcEdge - randomAggregation - dstEdge - dst
					isSamePod = true;
					DatapathId srcEdge = srcLinks.iterator().next().getDst();
					DatapathId dstEdge = dstLinks.iterator().next().getDst();
					DatapathId aggregation = null;
					int index = random.nextInt(pod.aggregations.size());
					Iterator<DatapathId> iter = pod.aggregations.iterator();
					while (index >= 0) {
						aggregation = iter.next();
						index--;
					}
					Link srcToEdge = srcDstLinkMap.get(sd.src).get(srcEdge);
					Link edgeToAgg = srcDstLinkMap.get(srcEdge)
							.get(aggregation);
					Link aggToEdge = srcDstLinkMap.get(aggregation)
							.get(dstEdge);
					Link edgeToDst = srcDstLinkMap.get(dstEdge).get(sd.dst);
					Link[] todoLink = new Link[] { srcToEdge, edgeToAgg,
							aggToEdge, edgeToDst };
					for (Link link : todoLink) {
						if (costMap.containsKey(link)) {
							costMap.put(link, sd.demandMb + costMap.get(link));
						} else {
							costMap.put(link, sd.demandMb);
						}
					}
					// add route
					List<NodePortTuple> nptList = new ArrayList<NodePortTuple>();
					nptList.add(new NodePortTuple(sd.src, srcToEdge.getSrcPort()));
					nptList.add(new NodePortTuple(srcEdge, srcToEdge.getDstPort()));
					nptList.add(new NodePortTuple(srcEdge, edgeToAgg.getSrcPort()));
					nptList.add(new NodePortTuple(aggregation, edgeToAgg.getDstPort()));
					nptList.add(new NodePortTuple(aggregation, aggToEdge.getSrcPort()));
					nptList.add(new NodePortTuple(dstEdge, aggToEdge.getDstPort()));
					nptList.add(new NodePortTuple(dstEdge, edgeToDst.getSrcPort()));
					nptList.add(new NodePortTuple(sd.dst, edgeToDst.getDstPort()));
					routes.put(sd, new Route(new RouteId(sd.src, sd.dst), nptList));
					break;
				}
				if (pod.hosts.contains(sd.src) || pod.hosts.contains(sd.dst)) {
					// different pod
					isSamePod = false;
					break;
				}
			}
			if (isSamePod)
				continue;
			// different pod
			// src - srcEdge - srcAggregation - stateDstCore - dstAggregation - dstEdge - dst
			Pod srcPod = null;
			Pod dstPod = null;
			for (Pod pod : podSet) {
				if (pod.hosts.contains(sd.src)) {
					srcPod = pod;
				}
				if (pod.hosts.contains(sd.dst)) {
					dstPod = pod;
				}
				if (srcPod != null && dstPod != null) {
					break;
				}
			}
			DatapathId srcEdge = srcLinks.iterator().next().getDst();
			DatapathId dstEdge = dstLinks.iterator().next().getDst();
			DatapathId srcAggregation = null;
			DatapathId dstAggregation = null;
			DatapathId core = state.get(sd.dst);
			for (Link link : linkMap.get(core)) {
				if (srcPod.aggregations.contains(link.getDst())) {
					srcAggregation = link.getDst();
				}
				if (dstPod.aggregations.contains(link.getDst())) {
					dstAggregation = link.getDst();
				}
				if (srcAggregation != null && dstAggregation != null) {
					break;
				}
			}
			Link srcToEdge = srcDstLinkMap.get(sd.src).get(srcEdge);
			Link edgeToAgg = srcDstLinkMap.get(srcEdge).get(srcAggregation);
			Link aggToCore = srcDstLinkMap.get(srcAggregation).get(core);
			Link coreToAgg = srcDstLinkMap.get(core).get(dstAggregation);
			Link aggToEdge = srcDstLinkMap.get(dstAggregation).get(dstEdge);
			Link edgeToDst = srcDstLinkMap.get(dstEdge).get(sd.dst);
			Link[] todoLink = new Link[] { srcToEdge, edgeToAgg, aggToCore,
					coreToAgg, aggToEdge, edgeToDst };
			for (Link link : todoLink) {
				if (costMap.containsKey(link)) {
					costMap.put(link, sd.demandMb + costMap.get(link));
				} else {
					costMap.put(link, sd.demandMb);
				}
			}
			// add route
			List<NodePortTuple> nptList = new ArrayList<NodePortTuple>();
			nptList.add(new NodePortTuple(sd.src, srcToEdge.getSrcPort()));
			nptList.add(new NodePortTuple(srcEdge, srcToEdge.getDstPort()));
			nptList.add(new NodePortTuple(srcEdge, edgeToAgg.getSrcPort()));
			nptList.add(new NodePortTuple(srcAggregation, edgeToAgg.getDstPort()));
			nptList.add(new NodePortTuple(srcAggregation, aggToCore.getSrcPort()));
			nptList.add(new NodePortTuple(core, aggToCore.getDstPort()));
			nptList.add(new NodePortTuple(core, coreToAgg.getSrcPort()));
			nptList.add(new NodePortTuple(dstAggregation, coreToAgg.getDstPort()));
			nptList.add(new NodePortTuple(dstAggregation, aggToEdge.getSrcPort()));
			nptList.add(new NodePortTuple(dstEdge, aggToEdge.getDstPort()));
			nptList.add(new NodePortTuple(dstEdge, edgeToDst.getSrcPort()));
			nptList.add(new NodePortTuple(sd.dst, edgeToDst.getDstPort()));
			routes.put(sd, new Route(new RouteId(sd.src, sd.dst), nptList));
			break;
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

	private Map<DatapathId, DatapathId> generateNeighbor() {
		hostSet.iterator();
		// random get 2 hosts
		int index1 = random.nextInt(hostSet.size());
		int index2 = random.nextInt(hostSet.size());
		DatapathId host1 = null;
		DatapathId host2 = null;
		Iterator<DatapathId> iter = hostSet.iterator();
		while (index1 >= 0) {
			host1 = iter.next();
			index1--;
		}
		iter = hostSet.iterator();
		while (index2 >= 0) {
			host2 = iter.next();
			index2--;
		}
		// switch 2 cores
		DatapathId core1 = currState.get(host1);
		DatapathId core2 = currState.get(host2);
		Map<DatapathId, DatapathId> res = new HashMap<DatapathId, DatapathId>();
		for (Entry<DatapathId, DatapathId> entry : currState.entrySet()) {
			if (entry.getKey().equals(host1) || entry.getKey().equals(host2)) {
				continue;
			}
			res.put(entry.getKey(), entry.getValue());
		}
		res.put(host1, core2);
		res.put(host2, core1);
		return res;
	}

	private Map<DatapathId, DatapathId> copyState(
			Map<DatapathId, DatapathId> state) {
		Map<DatapathId, DatapathId> res = new HashMap<DatapathId, DatapathId>();
		for (Entry<DatapathId, DatapathId> entry : state.entrySet()) {
			res.put(entry.getKey(), entry.getValue());
		}
		return res;
	}
	
	private Map<SrcDst, Route> copyRoute(Map<SrcDst, Route> routes) {
		Map<SrcDst, Route> res = new HashMap<SrcDst, Route>();
		for (Entry<SrcDst, Route> entry : routes.entrySet()) {
			res.put(entry.getKey(), new Route(entry.getValue().getId(), entry.getValue().getPath()));
		}
		return res;
	}

	/**
	 * ISimulateAnnealService Implement
	 */
	@Override
	public void receiveActiveFlow(Map<DatapathId, List<OFStatsReply>> model) {
		todoFlowSet.clear();
		for (Entry<DatapathId, List<OFStatsReply>> entry : model.entrySet()) {
			for (OFStatsReply r : (List<OFStatsReply>) entry.getValue()) {
				OFFlowStatsReply fsr = (OFFlowStatsReply) r;
				for (OFFlowStatsEntry fse : fsr.getEntries()) {
					OFMatchV3 match = (OFMatchV3) fse.getMatch();
					MacAddress srcMac = match.get(MatchField.ETH_SRC);
					MacAddress dstMac = match.get(MatchField.ETH_DST);
					DatapathId srcDpid = DatapathId.of(srcMac);
					DatapathId dstDpid = DatapathId.of(dstMac);
					// compute demand
					String srcMacStr = srcMac.toString();
					String dstMacStr = dstMac.toString();
					int demandMb = 0;
					for (int i = 0; i < srcMacStr.length(); i++) {
						demandMb += Integer.parseInt(String.valueOf(srcMacStr.charAt(i)));
					}
					for (int i = 0; i < dstMacStr.length(); i++) {
						demandMb += Integer.parseInt(String.valueOf(dstMacStr.charAt(i)));
					}
					demandMb = demandMb % 1000;
					todoFlowSet.add(new SrcDst(srcDpid, dstDpid, srcMac, dstMac, demandMb));
				}
			}
		}
		sa();
	}

	/**
	 * IFloodlightModule Implement
	 */
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
		l.add(ISimulateAnnealService.class);
		return l;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		Map<Class<? extends IFloodlightService>, IFloodlightService> m = new HashMap<Class<? extends IFloodlightService>, IFloodlightService>();
		m.put(ISimulateAnnealService.class, this);
		return m;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
		l.add(ITopologyService.class);
		l.add(IDeviceService.class);
		l.add(IOFSwitchService.class);
		l.add(IStaticFlowEntryPusherService.class);
		return l;
	}

	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		topologyService = context.getServiceImpl(ITopologyService.class);
		deviceService = context.getServiceImpl(IDeviceService.class);
		switchService = context.getServiceImpl(IOFSwitchService.class);
		staticFlowEntryPusherService = context.getServiceImpl(IStaticFlowEntryPusherService.class);
		logger = LoggerFactory.getLogger(SA2.class);

		hostSet = new HashSet<DatapathId>();
		edgeSwitchSet = new HashSet<DatapathId>();
		aggregationSwitchSet = new HashSet<DatapathId>();
		coreSwitchSet = new HashSet<DatapathId>();
		todoFlowSet = new HashSet<SrcDst>();
		srcDstLinkMap = new HashMap<DatapathId, Map<DatapathId, Link>>();
		podSet = new HashSet<Pod>();
		random = new Random();
	}

	@Override
	public void startUp(FloodlightModuleContext context)
			throws FloodlightModuleException {

	}

}
