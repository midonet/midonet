/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */

package org.midonet.sdn.flows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import scala.collection.JavaConversions;

import akka.actor.ActorSystem;
import com.typesafe.config.ConfigFactory;
import org.junit.Before;
import org.junit.Test;

import org.midonet.midolman.flows.WildcardTablesProvider;
import org.midonet.odp.Flow;
import org.midonet.odp.FlowMatch;
import org.midonet.odp.flows.FlowAction;
import org.midonet.odp.flows.FlowKeys;
import org.midonet.util.functors.Callback1;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsNull.nullValue;

public class FlowManagerTest {

    long maxDpFlowSize = 5;
    long maxWildcardFlowSize = 5;
    int dpFlowRemoveBatchSize = 2;
    final FlowManagerHelperImpl flowManagerHelper = new FlowManagerHelperImpl();
    FlowManager flowManager;
    int timeOut = 100;
    long idleFlowToleranceInterval = 2;
    Lock updateFlowLock = new ReentrantLock();

    WildcardTablesProvider wildtablesProvider = new WildcardTablesProvider() {
        Map<Set<WildcardMatch.Field>, Map<WildcardMatch, ManagedWildcardFlow>> tables =
                new HashMap<Set<WildcardMatch.Field>, Map<WildcardMatch, ManagedWildcardFlow>>();

        @Override
        public Map<WildcardMatch, ManagedWildcardFlow> addTable(Set<WildcardMatch.Field> pattern) {
            Map<WildcardMatch, ManagedWildcardFlow> table = tables().get(pattern);
            if (table == null) {
                table = new HashMap<WildcardMatch, ManagedWildcardFlow>();
                tables.put(pattern, table);
            }
            return table;
        }

        @Override
        public Map<Set<WildcardMatch.Field>, Map<WildcardMatch, ManagedWildcardFlow>> tables() {
            return tables;
        }
    };

    @Before
    public void setUp() {
        // This is only used for logging
        ActorSystem actorSystem = ActorSystem.create("MidolmanActorsTest", ConfigFactory
            .load().getConfig("midolman"));

        flowManager = new FlowManager(flowManagerHelper,
                wildtablesProvider,
                maxDpFlowSize, maxWildcardFlowSize, idleFlowToleranceInterval,
                actorSystem.eventStream(),
                dpFlowRemoveBatchSize);
    }

    @Test
    public void testHardTimeExpiration() throws InterruptedException {

        FlowMatch flowMatch =
            new FlowMatch().addKey(FlowKeys.tunnel(10L, 100, 200));

        WildcardMatch wildcardMatch = WildcardMatch.fromFlowMatch(flowMatch);

        WildcardFlow wildcardFlow =
            WildcardFlowFactory.createHardExpiration(wildcardMatch, timeOut);

        Flow flow = new Flow(flowMatch, actionsAsJava(wildcardFlow));

        int numberOfFlowsAdded = 0;
        ManagedWildcardFlow wflow = ManagedWildcardFlow.create(wildcardFlow);
        flowManager.add(wflow);
        flowManager.add(flow, wflow);
        // add flow
        flowManagerHelper.addFlow(new Flow(flowMatch));
        numberOfFlowsAdded++;
        assertThat("DpFlowTable was not updated",
                   flowManager.getDpFlowTable().size(),
                   equalTo(numberOfFlowsAdded));

        assertThat("WildcardFlowsToDpFlows was not updated",
                   flowManager.getNumWildcardFlows(),
                   equalTo(numberOfFlowsAdded));

        assertThat("DpFlowToWildFlow table was not updated",
                   flowManager.getWildcardTables().get(
                                   wflow.getMatch().getUsedFields())
                              .get(wflow.getMatch()),
                   equalTo(wflow));

        Thread.sleep(timeOut);
        // the flow should be expired since timeLived > timeOut
        flowManager.checkFlowsExpiration();

        assertThat("Flow was not deleted",
                   flowManagerHelper.flowsMap.get(flowMatch),
                   nullValue());
        assertThat("Flow was not deleted from ManagedWildcardFlow",
                   !wflow.dpFlows().contains(flowMatch));
    }

    @Test
    public void testRemoveAddRace() {
        FlowMatch flowMatch =
                new FlowMatch().addKey(FlowKeys.tunnel(10L, 100, 200));
        WildcardMatch wildcardMatch = WildcardMatch.fromFlowMatch(flowMatch);
        WildcardFlow wildcardFlow =
                WildcardFlowFactory.createIdleExpiration(wildcardMatch, timeOut);
        Flow flow = new Flow(flowMatch, actionsAsJava(wildcardFlow));
        ManagedWildcardFlow wflow = ManagedWildcardFlow.create(wildcardFlow);

        flowManager.add(wflow);
        flowManager.add(flow, wflow);

        assertThat("DpFlowTable was not updated",
                flowManager.getDpFlowTable().size(),
                equalTo(1));

        flowManager.remove(wflow);

        ManagedWildcardFlow wflow2 = ManagedWildcardFlow.create(wildcardFlow);
        Flow flow2 = new Flow(flowMatch, actionsAsJava(wflow2));
        flowManager.add(wflow2);
        flowManager.add(flow2, wflow2);

        assertThat("DpFlowTable couldn't handle racy flow add/remove ops",
                flowManager.getDpFlowTable().size(),
                equalTo(1));
    }

    @Test
    public void testIdleExpiration() throws InterruptedException {
        FlowMatch flowMatch =
            new FlowMatch().addKey(FlowKeys.tunnel(10L, 100, 200));

        WildcardMatch wildcardMatch = WildcardMatch.fromFlowMatch(flowMatch);

        WildcardFlow wildcardFlow =
            WildcardFlowFactory.createIdleExpiration(wildcardMatch, timeOut);
        Flow flow = new Flow(flowMatch, actionsAsJava(wildcardFlow));
        int numberOfFlowsAdded = 0;
        ManagedWildcardFlow wflow = ManagedWildcardFlow.create(wildcardFlow);
        flowManager.add(wflow);
        flowManager.add(flow, wflow);
        flowManagerHelper.addFlow(new Flow(flowMatch));
        numberOfFlowsAdded++;
        assertThat("DpFlowTable was not updated",
                   flowManager.getDpFlowTable().size(),
                   equalTo(numberOfFlowsAdded));

        assertThat("WildcardFlowsToDpFlows was not updated",
                   flowManager.getNumWildcardFlows(),
                   equalTo(numberOfFlowsAdded));

        assertThat("DpFlowToWildFlow table was not updated",
                   flowManager.getWildcardTables().get(
                                   wflow.getMatch().getUsedFields())
                              .get(wflow.getMatch()),
                   equalTo(wflow));

        Thread.sleep(timeOut+idleFlowToleranceInterval);

        // the flow should be expired since lastUsedTime > timeOut
        flowManager.checkFlowsExpiration();

        // checkFlowsExpiration calls getFlow that runs on another thread, getFlow
        // must complete before checking the status of the maps. I hate sleeps
        // but didn't want to make the code more complex, this is quick and dirty
        Thread.sleep(10);
        assertThat("Flow was not deleted",
                   flowManagerHelper.flowsMap.get(flowMatch),
                   nullValue());
        assertThat("Flow was not deleted from ManagedWildcardFlow",
                   !wflow.dpFlows().contains(flowMatch));
    }


    @Test
    public void testIdleExpirationUpdate() throws InterruptedException{
        FlowMatch flowMatch =
            new FlowMatch().addKey(FlowKeys.tunnel(10L, 100, 200));

        WildcardMatch wildcardMatch = WildcardMatch.fromFlowMatch(flowMatch);
        WildcardFlow wildcardFlow =
            WildcardFlowFactory.createIdleExpiration(wildcardMatch, timeOut);
        Flow flow = new Flow(flowMatch, actionsAsJava(wildcardFlow));

        int numberOfFlowsAdded = 0;
        ManagedWildcardFlow wflow = ManagedWildcardFlow.create(wildcardFlow);
        flowManager.add(wflow);
        flowManager.add(flow, wflow);
        flowManagerHelper.addFlow(new Flow(flowMatch));
        numberOfFlowsAdded++;

        Thread.sleep(timeOut/2);

        // add another flow that matches, that will update the LastUsedTime of a
        // value > timeOut/2
        FlowMatch flowMatch1 =
            new FlowMatch().addKey(FlowKeys.tunnel(10L, 100, 200))
                           .addKey(FlowKeys.tcp(1000, 1002));
        Flow flow2 =
            new Flow(flowMatch1, actionsAsJava(wflow));

        // create the flow
        flowManager.add(flow2, wflow);
        flowManagerHelper.addFlow(flow2);

        numberOfFlowsAdded++;

        assertThat("DpFlowTable was not updated",
                   flowManager.getDpFlowTable().size(),
                   equalTo(numberOfFlowsAdded));

        assertThat("WildcardFlowsToDpFlows was not updated",
                flowManager.getWildcardFlow(wflow.getMatch()).dpFlows().size(),
                equalTo(numberOfFlowsAdded));

        assertThat("DpFlowToWildFlow table was not updated",
                   flowManager.getWildcardTables().get(
                       wflow.getMatch().getUsedFields())
                              .get(wflow.getMatch()),
                   equalTo(wflow));

        Thread.sleep(timeOut/2);
        // this call will check the flow expiration and if a flow has timeLived >
        // idle-timeout, an update about the LastUsedTime will be requested
        // from the kernel
        flowManager.checkFlowsExpiration();

        // wildcard flow should still be there
        assertThat("DpFlowToWildFlow table was not updated",
                   flowManager.getWildcardTables().get(
                                   wflow.getMatch().getUsedFields())
                              .get(wflow.getMatch()),
                   equalTo(wflow));

        assertThat("Flow was deleted from ManagedWildcardFlow",
                   wflow.dpFlows().contains(flowMatch));

        Thread.sleep(timeOut);

        flowManager.checkFlowsExpiration();
        // checkFlowsExpiration calls getFlow that runs on another thread, getFlow
        // must complete before checking the status of the maps. I hate sleeps
        // but didn't want to make the code more complex, this is quick and dirty
        Thread.sleep(10);

        // both should be deleted
        assertThat("Flow was not deleted",
                   flowManagerHelper.flowsMap.get(flowMatch),
                   nullValue());

        assertThat("Wildcard flow wasn't deleted",
                   flowManager.getWildcardTables().size(),
                   equalTo(0));

        assertThat("Flow was not deleted from ManagedWildcardFlow",
                   !wflow.dpFlows().contains(flowMatch));
    }

    @Test
    public void wildcardFlowUpdatedBecauseOfKernelFlowUpdated()
            throws InterruptedException {
        FlowMatch flowMatch =
            new FlowMatch().addKey(FlowKeys.tunnel(10L, 100, 200));

        WildcardMatch wildcardMatch = WildcardMatch.fromFlowMatch(flowMatch);
        WildcardFlow wildcardFlow =
            WildcardFlowFactory.createIdleExpiration(wildcardMatch, timeOut);
        Flow flow = new Flow(flowMatch, actionsAsJava(wildcardFlow));


        ManagedWildcardFlow wflow = ManagedWildcardFlow.create(wildcardFlow);
        flowManager.add(wflow);
        flowManager.add(flow, wflow);
        flowManagerHelper.addFlow(new Flow(flowMatch));

        Thread.sleep(timeOut);

        // update the flow LastUsedTime in the kernel (FlowManagerHelperImpl is
        // mocking the kernel in this test)
        flowManagerHelper.setLastUsedTimeToNow(flowMatch);

        // checkFlowsExpiration will iterate through the priority queue for idle
        // expiration. For the expired flow it will do a FlowManagerHelper.getFlow
        // and pass a callback. The callback will modify the priority queue.
        // In this test if we use just one thread we get a ConcurrentModificationException
        // because the queue is modified while iterating. That's why the callback
        // runs in another thread and we need to synchronize
        // In MM the synchronization will be done by Akka, since the callback is
        // triggered by a message to the FlowController
        updateFlowLock.lock();
        flowManager.checkFlowsExpiration();
        updateFlowLock.unlock();
        Thread.sleep(timeOut/2);

        assertThat("Wildcard flow LastUsedTime was not updated",
                   wflow.getLastUsedTimeMillis(),
                   equalTo(flowManagerHelper.flowsMap.get(flowMatch)
                                            .getLastUsedTime()));
        flowManager.checkFlowsExpiration();
        // wildcard flow should still be there
        assertThat("DpFlowToWildFlow table was not updated",
                   flowManager.getWildcardTables().get(
                                   wflow.getMatch().getUsedFields())
                              .get(wflow.getMatch()),
                   equalTo(wflow));
        assertThat("Flow was deleted from ManagedWildcardFlow",
                   wflow.dpFlows().contains(flowMatch));

        Thread.sleep(timeOut);
        updateFlowLock.lock();
        flowManager.checkFlowsExpiration();
        updateFlowLock.unlock();

        Thread.sleep(5);

        // both should be deleted
        assertThat("Flow was not deleted",
                   flowManagerHelper.flowsMap.get(flowMatch),
                   nullValue());

        assertThat("Wildcard flow wasn't deleted",
                   flowManager.getWildcardTables().size(),
                   equalTo(0));

        assertThat("Flow was not deleted from ManagedWildcardFlow",
                   !wflow.dpFlows().contains(flowMatch));
    }

    @Test
    public void testFreeSpaceDpTable(){
        int maxAcceptedDpFlows = (int) (maxDpFlowSize - dpFlowRemoveBatchSize);
        // fill the table with a number of flow > maxAcceptedDpFlows
        FlowMatch firstFlowMatch = null;

        FlowMatch baseDpMatch =
                new FlowMatch().addKey(FlowKeys.tunnel(1, 100, 200));
        WildcardMatch wcMatch =
                WildcardMatch.fromFlowMatch(baseDpMatch);
        WildcardFlow wcFlow =
                WildcardFlowFactory.create(wcMatch);
        ManagedWildcardFlow managedFlow = ManagedWildcardFlow.create(wcFlow);
        flowManager.add(managedFlow);

        for (int i=0; i<=maxAcceptedDpFlows; i++) {
            FlowMatch flowMatch =
                new FlowMatch().addKey(FlowKeys.tunnel(1, 100, 200));
            flowMatch.addKey(FlowKeys.etherType((short)(23+i)));

            Flow flow = new Flow(flowMatch, actionsAsJava(wcFlow));
            flowManager.add(flow, managedFlow);
            flowManagerHelper.addFlow(flow);

            if (i == 0) {
                firstFlowMatch = flowMatch;
            }
        }

        flowManager.checkFlowsExpiration();

        assertThat("DpFlowTable, a flow hasn't been removed",
                   flowManager.getDpFlowTable().size(),
                   equalTo(maxAcceptedDpFlows));

        assertThat("First flow was not deleted",
                   flowManagerHelper.flowsMap.get(firstFlowMatch),
                   nullValue());
    }

    @Test
    public void testMaximumWildcardFlows() {
        int testSize = 6;
        List<ManagedWildcardFlow> flows = new ArrayList<ManagedWildcardFlow>(testSize);

        for (int counter = 0; counter < testSize; counter++) {
            FlowMatch flowMatch =
                new FlowMatch().addKey(FlowKeys.tunnel(counter * 10L, 100, 200));
            WildcardMatch wildcardMatch = WildcardMatch.fromFlowMatch(flowMatch);
            WildcardFlow wf = WildcardFlowFactory.create(wildcardMatch);
            flows.add(ManagedWildcardFlow.create(wf));

        }

        assertThat("FlowManager didn't accept the first wildcard flow", flowManager.add(flows.get(0)));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(1));
        assertThat("FlowManager didn't accept the second wildcard flow", flowManager.add(flows.get(1)));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(2));
        assertThat("FlowManager didn't accept the third wildcard flow", flowManager.add(flows.get(2)));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(3));
        assertThat("FlowManager didn't accept the fourth wildcard flow", flowManager.add(flows.get(3)));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(4));
        assertThat("FlowManager didn't accept the fifth wildcard flow", flowManager.add(flows.get(4)));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(5));
        assertThat("FlowManager didn't reject the last wildcard flow", !flowManager.add(flows.get(5)));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(5));
    }

    @Test
    public void testMaximumWildcardFlowsWithExpiration() throws InterruptedException {
        int testSize = 6;
        List<ManagedWildcardFlow> flows = new ArrayList<ManagedWildcardFlow>(testSize);

        for (int counter = 0; counter < testSize; counter++) {
            FlowMatch flowMatch =
                new FlowMatch().addKey(FlowKeys.tunnel(counter * 10L, 100, 200));
            WildcardMatch wildcardMatch = WildcardMatch.fromFlowMatch(flowMatch);
            WildcardFlow wf = WildcardFlowFactory.createHardExpiration(wildcardMatch, timeOut);
            flows.add(ManagedWildcardFlow.create(wf));
        }

        assertThat("FlowManager didn't accept the first wildcard flow", flowManager.add(flows.get(0)));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(1));
        assertThat("FlowManager didn't accept the second wildcard flow", flowManager.add(flows.get(1)));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(2));
        assertThat("FlowManager didn't accept the third wildcard flow", flowManager.add(flows.get(2)));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(3));
        assertThat("FlowManager didn't accept the fourth wildcard flow", flowManager.add(flows.get(3)));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(4));
        assertThat("FlowManager didn't accept the fifth wildcard flow", flowManager.add(flows.get(4)));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(5));

        Thread.sleep(timeOut);
        // all wildcardflows should have been expired now, but they will be there.
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(5));
        // when adding a new wildcard flow it will clean the expired flows.
        assertThat("FlowManager didn't accept the new wildcard flow", flowManager.add(flows.get(5)));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(),
            equalTo((int)maxWildcardFlowSize - dpFlowRemoveBatchSize + 1));
    }

    private List<FlowAction>actionsAsJava(WildcardFlow wflow) {
        return JavaConversions.asJavaList(wflow.actions());
    }

    // Implementation of the FlowManagerHelper for this test
    class FlowManagerHelperImpl implements FlowManagerHelper {

        public Map<FlowMatch, Flow> flowsMap = new HashMap<FlowMatch, Flow>();
        public Queue<Flow> toRemove;

        public void addFlow(Flow flow) {
            flow.setLastUsedTime(System.currentTimeMillis());
            flowsMap.put(flow.getMatch(), flow);
        }

        public void setLastUsedTimeToNow(FlowMatch match) {
            flowsMap.get(match).setLastUsedTime(System.currentTimeMillis());
        }

        private void doRemoveFlow(Flow flow) {
            flowsMap.remove(flow.getMatch());
        }

        @Override
        public void removeFlow(FlowMatch flowMatch) {
            if (toRemove != null)
                toRemove.add(new Flow(flowMatch));
            else
                doRemoveFlow(new Flow(flowMatch));
        }

        @Override
        public void removeWildcardFlow(ManagedWildcardFlow flow) {
            toRemove = new LinkedList<>();
            flowManager.remove(flow);
            for (Flow f : toRemove) {
                doRemoveFlow(f);
            }
            toRemove = null;
        }

        @Override
        public void getFlow(FlowMatch flowMatch, Callback1<Flow> flowCb) {
            new Thread(new MockFlowUpdatedMessageRunnable(flowMatch, flowCb)).start();
        }

        class MockFlowUpdatedMessageRunnable implements Runnable {
            Callback1<Flow> flowCb;
            FlowMatch flowMatch;

            MockFlowUpdatedMessageRunnable(FlowMatch flowMatch,
                                           Callback1<Flow> flowCb) {
                this.flowCb = flowCb;
                this.flowMatch = flowMatch;
            }

            @Override
            public void run() {
                updateFlowLock.lock();
                flowCb.call(flowsMap.get(flowMatch));
                updateFlowLock.unlock();
            }
        }
    }
}
