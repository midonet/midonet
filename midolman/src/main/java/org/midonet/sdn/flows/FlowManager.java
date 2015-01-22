/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.sdn.flows;

import java.util.Comparator;
import java.util.Iterator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.odp.Flow;
import org.midonet.odp.FlowMatch;
import org.midonet.util.functors.Callback1;

// not thread-safe
//TODO(ross) check the values mentioned in the doc below
/**
 * Hard Time-out
 * This class guarantees that every wildcard flow that has an hard time-out set
 * will be evicted after hard time-out + delta, where delta depends on how
 * frequently the Scheduler triggers the check for flow expiration and how
 * long the operation takes.
 * We will test that and provide an estimate.
 *
 * Idle Time-out
 * This class guarantees that every wildcard flow that has an idle time-out set
 * will be evicted after idle time-out + delta.
 * We have two priority queues, one for hard time-out expiration and the other
 * for idle time-out expiration. They are ordered according to the time that
 * each flow has remaining.
 * For idle time-out before deleting a wildcard flow, we get from the datapath
 * connection the lastUsedTime of each microflow until we find one whose
 * lastUsedTime < now - timeout. If that's the case, we extend the life of the
 * wildcard flow, otherwise we delete it.
 *
 * Idle Time-out expiration is an expensive operation. We won't accept
 * idle time-out < 5 s.
 */

//TODO(ross) create a priority queue of micro flows ordered according to the
// lastUsedTime we got from the kernel. When we have to free space we will
// delete the oldest one.
public class FlowManager {
    private static int DEFAULT_FLOW_REMOVE_BATCH_SIZE = 512;

    private Logger log = LoggerFactory.getLogger("org.midonet.flow-management");
    private static final long NO_LIMIT = Long.MAX_VALUE;

    private FlowManagerHelper flowManagerHelper;
    private long maxDpFlows;
    private int dpFlowRemoveBatchSize;
    private long idleFlowToleranceInterval;

    public FlowManager(FlowManagerHelper flowManagerHelper, long maxDpFlows,
                       long idleFlowToleranceInterval) {
        this(flowManagerHelper, maxDpFlows, idleFlowToleranceInterval,
             DEFAULT_FLOW_REMOVE_BATCH_SIZE);
    }

    public FlowManager(FlowManagerHelper flowManagerHelper, long maxDpFlows,
                       long idleFlowToleranceInterval, int dpFlowRemoveBatchSize) {
        this.maxDpFlows = maxDpFlows;
        this.idleFlowToleranceInterval = idleFlowToleranceInterval;
        this.flowManagerHelper = flowManagerHelper;
        if (dpFlowRemoveBatchSize > maxDpFlows)
            dpFlowRemoveBatchSize = 1;
        this.dpFlowRemoveBatchSize = dpFlowRemoveBatchSize;
    }

    public HashMap<FlowMatch, ManagedFlow> dpFlowTable = new HashMap<>((int)maxDpFlows);

    //TODO(ross) size for the priority queue?
    final int priorityQueueSize = 10000;
    /* Priority queue to evict flows based on hard time-out */
    private PriorityQueue<ManagedFlow> hardTimeOutQueue =
        new PriorityQueue<>(priorityQueueSize, new WildcardFlowHardTimeComparator());

    /* Priority queue to evict flows based on idle time-out */
    private PriorityQueue<ManagedFlow> idleTimeOutQueue =
        new PriorityQueue<>(priorityQueueSize, new WildcardFlowIdleTimeComparator());

    public int getNumDpFlows() {
        return dpFlowTable.size();
    }

    public int evictOldestFlows() {
        int evicted;
        for (evicted = 0; evicted < dpFlowRemoveBatchSize; evicted++) {
            if (!evictOneFlow())
                break;
        }
        return evicted;
    }

    public boolean evictOneFlow() {
        ManagedFlow toEvict = !hardTimeOutQueue.isEmpty() ?
                hardTimeOutQueue.poll() : idleTimeOutQueue.poll();

        if (toEvict != null) {
            flowManagerHelper.removeWildcardFlow(toEvict);
            // timeout queue ref
            toEvict.unref();
            return true;
        } else {
            return false;
        }
    }

    /**
     * Add a new wildcard flow.
     *
     * @param wildFlow
     * @return True iff the wildcard flow was added. The flow will not be
     *         added if the table already contains a wildcard flow with the same
     *         match.
     */
    public boolean add(ManagedFlow wildFlow) {
        if (getNumDpFlows() >= maxDpFlows) {
            if (evictOldestFlows() == 0) {
                log.error("Could not add the new flow as the system reached its maximum");
                return false;
            }
        }

        FlowMatch fmatch = wildFlow.flowMatch();
        if (!dpFlowTable.containsKey(fmatch)) {
            dpFlowTable.put(fmatch, wildFlow);
            // FlowManager's ref
            wildFlow.ref();
            wildFlow.setCreationTimeMillis(System.currentTimeMillis());
            wildFlow.setLastUsedTimeMillis(System.currentTimeMillis());
            if (wildFlow.hardExpirationMillis() > 0) {
                // timeout queue ref
                wildFlow.ref();
                hardTimeOutQueue.add(wildFlow);
            } else if (wildFlow.idleExpirationMillis() > 0){
                // timeout queue ref
                wildFlow.ref();
                idleTimeOutQueue.add(wildFlow);
            }
            return true;
        }
        return false;
    }

    /**
     * Remove a wildcard flow and its associated datapath flows.
     *
     * @param wildFlow
     * @return true if the flow was alive, false otherwise
     */
    public boolean remove(ManagedFlow wildFlow) {
        log.debug("Removing flow {}", wildFlow.flowMatch());

        FlowMatch flowMatch = wildFlow.flowMatch();
        if (dpFlowTable.remove(flowMatch) != null) {
            flowManagerHelper.removeFlow(wildFlow.flowMatch());
            wildFlow.unref(); // FlowManager's ref
            return true;
        }
        return false;
    }

    private void checkHardTimeOutExpiration() {
        ManagedFlow flowToExpire;
        while ((flowToExpire = hardTimeOutQueue.peek()) != null) {
            // since we remove the element lazily let's check if this el
            // has already been removed
            if(!isAlive(flowToExpire)) {
                hardTimeOutQueue.poll();
                // timeout queue ref
                flowToExpire.unref();
                continue;
            }
            long timeLived = System.currentTimeMillis() - flowToExpire.getCreationTimeMillis();
            if (timeLived >= flowToExpire.hardExpirationMillis()) {
                hardTimeOutQueue.poll();
                flowManagerHelper.removeWildcardFlow(flowToExpire); // will remove remaining refs
                log.debug("Removing flow {} for hard expiration, expired {} ms ago",
                          flowToExpire.flowMatch(),
                          timeLived - flowToExpire.hardExpirationMillis());
                // timeout queue ref
                flowToExpire.unref();
            } else {
                return;
            }
        }
    }

    private boolean isAlive(ManagedFlow flowToExpire) {
        return dpFlowTable.containsKey(flowToExpire.flowMatch());
    }

    private void getKernelFlowLastUsedTime(ManagedFlow flowToExpire) {
        FlowMatch flowMatch = flowToExpire.flowMatch();
        UpdateLastUsedTimeCallback callback = new UpdateLastUsedTimeCallback(flowToExpire);
        if (dpFlowTable.containsKey(flowMatch)) {
            // getFlow callback ref
            flowToExpire.ref();
            flowManagerHelper.getFlow(flowMatch, callback);
        } else {
            flowManagerHelper.removeWildcardFlow(flowToExpire);
        }
    }

    private void checkIdleTimeExpiration() {
        while (idleTimeOutQueue.peek() != null) {
            ManagedFlow flowToExpire = idleTimeOutQueue.peek();
            //log.trace("Idle timeout queue size {}", idleTimeOutQueue.size());
            // since we remove the element lazily let's check if this element
            // has already been removed
            if (!isAlive(flowToExpire)) {
                idleTimeOutQueue.poll();
                // timeout queue ref
                flowToExpire.unref();
                continue;
            }
            long expirationDate = flowToExpire.getLastUsedTimeMillis() +
                flowToExpire.idleExpirationMillis();
            // if the flow expired we don't delete it immediately, first we query
            // the kernel to get the updated lastUsedTime
            if (System.currentTimeMillis() >= expirationDate) {
                // remove it from the queue so we won't query it again
                idleTimeOutQueue.poll();
                getKernelFlowLastUsedTime(flowToExpire);
                // timeout queue ref
                flowToExpire.unref();
            }else
                break;
        }
    }

    private void manageDPFlowTableSpace() {
        removeOldestDpFlows((int) howManyFlowsToRemoveToFreeSpace());
    }

    private void removeOldestDpFlows(int nFlowsToRemove) {
        if (nFlowsToRemove <= 0)
            return;

        Iterator<ManagedFlow> it = dpFlowTable.values().iterator();
        while (it.hasNext() && nFlowsToRemove-- > 0) {
            flowManagerHelper.removeWildcardFlow(it.next());
            it.remove();
        }
    }

    private long howManyFlowsToRemoveToFreeSpace() {
        return (getNumDpFlows() - (maxDpFlows - dpFlowRemoveBatchSize));
    }

    public void checkFlowsExpiration() {
        checkHardTimeOutExpiration();
        //updateWildcardLastUsedTime();
        checkIdleTimeExpiration();
        // check if there's enough space in the DP table
        manageDPFlowTableSpace();
    }

    public void flowMissing(FlowMatch flowMatch) {
        ManagedFlow wildcardFlow = dpFlowTable.remove(flowMatch);
        if (wildcardFlow != null) {
            wildcardFlow.unref(); // FlowController's ref
            wildcardFlow.unref(); // FlowManager's ref
        }
    }

    private abstract class WildcardFlowComparator implements Comparator<ManagedFlow> {

        @Override
        public int compare(ManagedFlow wildcardFlow,
                           ManagedFlow wildcardFlow1) {
            long now = System.currentTimeMillis();
            long expirationTime1 = getExpirationTime(wildcardFlow, now);
            long expirationTime2 = getExpirationTime(wildcardFlow1, now);

            return (int) (expirationTime1-expirationTime2);
        }

        protected abstract long getExpirationTime(ManagedFlow flow, long now);
    }

    private class WildcardFlowHardTimeComparator extends WildcardFlowComparator {
        @Override
        protected long getExpirationTime(ManagedFlow flow, long now) {
            return flow.getCreationTimeMillis() + flow.hardExpirationMillis();
        }
    }

    private class WildcardFlowIdleTimeComparator extends WildcardFlowComparator {
        @Override
        protected long getExpirationTime(ManagedFlow flow, long now) {
            return flow.getLastUsedTimeMillis() + flow.idleExpirationMillis();
        }
    }

    /**
     * This callback is passed to flowsGet(). When flowsGet() returns with the
     * updated lastUsedTime we take a decision regarding the expiration of the
     * wcflow
     */
    class UpdateLastUsedTimeCallback implements Callback1<Flow> {
        ManagedFlow wcFlow;

        UpdateLastUsedTimeCallback(ManagedFlow wcFlow) {
            this.wcFlow = wcFlow;
        }

        @Override
        public void call(Flow flowGotFromKernel) {
            // the wildcard flow was deleted

            if (!isAlive(wcFlow)) {
                // getFlow callback ref
                wcFlow.unref();
                return;
            }

            if (flowGotFromKernel != null && flowGotFromKernel.getLastUsedTime() != null) {
                // update the lastUsedTime
                if (flowGotFromKernel.getLastUsedTime() > wcFlow.getLastUsedTimeMillis()) {
                    wcFlow.setLastUsedTimeMillis(flowGotFromKernel.getLastUsedTime());
                    log.trace("update lastUsedTime {}", flowGotFromKernel.getLastUsedTime());
                }
            }

            long expirationDate = wcFlow.getLastUsedTimeMillis() + wcFlow.idleExpirationMillis();
            if (expirationDate - System.currentTimeMillis() > idleFlowToleranceInterval) {
                // add it back to the queue
                // timeout queue ref
                wcFlow.ref();
                idleTimeOutQueue.add(wcFlow);
            } else {
                // we can expire it
                flowManagerHelper.removeWildcardFlow(wcFlow);
                log.debug(
                    "Removing flow {} for idle expiration, expired {} ms ago",
                    wcFlow.flowMatch(),
                    System.currentTimeMillis() - (wcFlow.getLastUsedTimeMillis()
                        + wcFlow.idleExpirationMillis()));
            }

            // getFlow callback ref
            wcFlow.unref();
        }
    }
}
