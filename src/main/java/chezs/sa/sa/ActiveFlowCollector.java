package chezs.sa.sa;

import java.lang.Thread.State;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.projectfloodlight.openflow.protocol.OFFeaturesReply;
import org.projectfloodlight.openflow.protocol.OFStatsReply;
import org.projectfloodlight.openflow.protocol.OFStatsType;
import org.projectfloodlight.openflow.types.DatapathId;

import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.web.SwitchResourceBase;
import net.floodlightcontroller.threadpool.IThreadPoolService;

public class ActiveFlowCollector extends SwitchResourceBase
		implements IFloodlightModule {

	private static IThreadPoolService threadPoolService;
	private static IOFSwitchService switchService;

	private static ScheduledFuture<?> activeFlowCollector;

	private class Collector implements Runnable {

		@Override
		public void run() {
			switchService = (IOFSwitchService) getContext().getAttributes()
					.get(IOFSwitchService.class.getCanonicalName());
			OFStatsType type = OFStatsType.FLOW;
			REQUESTTYPE rType = REQUESTTYPE.OFSTATS;
			HashMap<DatapathId, List<OFStatsReply>> model = new HashMap<DatapathId, List<OFStatsReply>>();

			Set<DatapathId> switchDpids = switchService.getAllSwitchDpids();
			List<GetConcurrentStatsThread> activeThreads = new ArrayList<GetConcurrentStatsThread>(
					switchDpids.size());
			List<GetConcurrentStatsThread> pendingRemovalThreads = new ArrayList<GetConcurrentStatsThread>();
			GetConcurrentStatsThread t;
			for (DatapathId l : switchDpids) {
				t = new GetConcurrentStatsThread(l, rType, type);
				activeThreads.add(t);
				t.start();
			}
			// Join all the threads after the timeout. Set a hard timeout
			// of 12 seconds for the threads to finish. If the thread has not
			// finished the switch has not replied yet and therefore we won't
			// add the switch's stats to the reply.
			for (int iSleepCycles = 0; iSleepCycles < 12; iSleepCycles++) {
				for (GetConcurrentStatsThread curThread : activeThreads) {
					if (curThread.getState() == State.TERMINATED) {
						model.put(curThread.getSwitchId(),
								curThread.getStatisticsReply());
						pendingRemovalThreads.add(curThread);
					}
				}

				// remove the threads that have completed the queries to the
				// switches
				for (GetConcurrentStatsThread curThread : pendingRemovalThreads) {
					activeThreads.remove(curThread);
				}
				// clear the list so we don't try to double remove them
				pendingRemovalThreads.clear();

				// if we are done finish early so we don't always get the worst
				// case
				if (activeThreads.isEmpty()) {
					break;
				}

				// sleep for 1 s here
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					log.error("Interrupted while waiting for statistics", e);
				}
			}
			ISimulateAnnealService sa = (ISimulateAnnealService) getContext()
					.getAttributes()
					.get(ISimulateAnnealService.class.getCanonicalName());
			if (sa != null) {
				sa.receiveActiveFlow(model);
			}
		}

	}

	protected class GetConcurrentStatsThread extends Thread {
		private List<OFStatsReply> switchReply;
		private DatapathId switchId;
		private OFStatsType statType;
		private REQUESTTYPE requestType;
		private OFFeaturesReply featuresReply;

		public GetConcurrentStatsThread(DatapathId switchId,
				REQUESTTYPE requestType, OFStatsType statType) {
			this.switchId = switchId;
			this.requestType = requestType;
			this.statType = statType;
			this.switchReply = null;
			this.featuresReply = null;
		}

		public List<OFStatsReply> getStatisticsReply() {
			return switchReply;
		}

		public OFFeaturesReply getFeaturesReply() {
			return featuresReply;
		}

		public DatapathId getSwitchId() {
			return switchId;
		}

		@Override
		public void run() {
			if ((requestType == REQUESTTYPE.OFSTATS) && (statType != null)) {
				switchReply = getSwitchStatistics(switchId, statType);
			} else if (requestType == REQUESTTYPE.OFFEATURES) {
				featuresReply = getSwitchFeaturesReply(switchId);
			}
		}
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		// Null
		return null;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		// Null
		return null;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IOFSwitchService.class);
		l.add(IThreadPoolService.class);
		return l;
	}

	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		threadPoolService = context.getServiceImpl(IThreadPoolService.class);
		switchService = context.getServiceImpl(IOFSwitchService.class);
	}

	@Override
	public void startUp(FloodlightModuleContext context)
			throws FloodlightModuleException {
		activeFlowCollector = threadPoolService.getScheduledExecutor()
				.scheduleAtFixedRate(new Collector(), 5, 5, TimeUnit.SECONDS);
	}
}
