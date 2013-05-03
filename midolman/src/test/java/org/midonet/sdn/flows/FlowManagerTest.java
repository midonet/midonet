/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.sdn.flows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import akka.actor.ActorSystem;
import com.typesafe.config.ConfigFactory;
import org.junit.Before;
import org.junit.Test;
import scala.collection.JavaConversions;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsNull.nullValue;

import org.midonet.midolman.flows.WildcardTablesProvider;
import org.midonet.odp.Flow;
import org.midonet.odp.FlowMatch;
import org.midonet.odp.flows.FlowAction;
import org.midonet.odp.flows.FlowKeys;


public class FlowManagerTest {

    long maxDpFlowSize = 5;
    long maxWildcardFlowSize = 5;
    int dpFlowRemoveBatchSize = 2;
    FlowManagerHelperImpl flowManagerHelper;
    FlowManager flowManager;
    int timeOut = 4000;

    WildcardTablesProvider wildtablesProvider = new WildcardTablesProvider() {
        Map<Set<WildcardMatch.Field>, Map<WildcardMatch, WildcardFlow>> tables =
                new HashMap<Set<WildcardMatch.Field>, Map<WildcardMatch, WildcardFlow>>();

        @Override
        public Map<WildcardMatch, WildcardFlow> addTable(Set<WildcardMatch.Field> pattern) {
            Map<WildcardMatch, WildcardFlow> table = tables().get(pattern);
            if (table == null) {
                table = new HashMap<WildcardMatch, WildcardFlow>();
                tables.put(pattern, table);
            }
            return table;
        }

        @Override
        public Map<Set<WildcardMatch.Field>, Map<WildcardMatch, WildcardFlow>> tables() {
            return tables;
        }
    };

    @Before
    public void setUp() {
        flowManagerHelper = new FlowManagerHelperImpl();
        // This is only used for loggging
        ActorSystem actorSystem = ActorSystem.create("MidolmanActorsTest", ConfigFactory
            .load().getConfig("midolman"));

        flowManager = new FlowManager(flowManagerHelper,
                wildtablesProvider,
                maxDpFlowSize, maxWildcardFlowSize, actorSystem.eventStream(),
                dpFlowRemoveBatchSize);
    }

    @Test
    public void testHardTimeExpiration() throws InterruptedException {

        FlowMatch flowMatch = new FlowMatch().addKey(FlowKeys.tunnelID(10L));

        WildcardMatch wildcardMatch = WildcardMatch.fromFlowMatch(flowMatch);

        WildcardFlowBuilder wildcardFlowBuilder = WildcardFlowBuilder.empty()
            .setMatch(wildcardMatch)
            .setHardExpirationMillis(timeOut);

        Flow flow = new Flow().
                        setMatch(flowMatch).
                        setActions(actionsAsJava(wildcardFlowBuilder));

        int numberOfFlowsAdded = 0;
        WildcardFlow wflow = wildcardFlowBuilder.build();
        flowManager.add(wflow);
        flowManager.add(flow, wflow);
        // add flow
        flowManagerHelper.addFlow(new Flow().setMatch(flowMatch));
        numberOfFlowsAdded++;
        assertThat("DpFlowTable was not updated",
                   flowManager.getDpFlowTable().size(),
                   equalTo(numberOfFlowsAdded));

        assertThat("WildcardFlowsToDpFlows was not updated",
                   flowManager.getNumWildcardFlows(),
                   equalTo(numberOfFlowsAdded));

        assertThat("DpFlowToWildFlow table was not updated",
                   flowManager.getDpFlowToWildFlow().size(),
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

    }

    @Test
    public void testIdleExpiration() throws InterruptedException {
        FlowMatch flowMatch = new FlowMatch().addKey(FlowKeys.tunnelID(10L));

        WildcardMatch wildcardMatch = WildcardMatch.fromFlowMatch(flowMatch);

        WildcardFlowBuilder wildcardFlowBuilder = WildcardFlowBuilder.empty()
            .setMatch(wildcardMatch)
            .setIdleExpirationMillis(timeOut);
        Flow flow = new Flow().setMatch(flowMatch).
                setActions(actionsAsJava(wildcardFlowBuilder));
        int numberOfFlowsAdded = 0;
        WildcardFlow wflow = wildcardFlowBuilder.build();
        flowManager.add(wflow);
        flowManager.add(flow, wflow);
        flowManagerHelper.addFlow(new Flow().setMatch(flowMatch));
        numberOfFlowsAdded++;
        assertThat("DpFlowTable was not updated",
                   flowManager.getDpFlowTable().size(),
                   equalTo(numberOfFlowsAdded));

        assertThat("WildcardFlowsToDpFlows was not updated",
                   flowManager.getNumWildcardFlows(),
                   equalTo(numberOfFlowsAdded));

        assertThat("DpFlowToWildFlow table was not updated",
                   flowManager.getDpFlowToWildFlow().size(),
                   equalTo(numberOfFlowsAdded));


        assertThat("DpFlowToWildFlow table was not updated",
                   flowManager.getWildcardTables().get(
                                   wflow.getMatch().getUsedFields())
                              .get(wflow.getMatch()),
                   equalTo(wflow));

        Thread.sleep(timeOut+1);

        // the flow should be expired since lastUsedTime > timeOut
        flowManager.checkFlowsExpiration();

        assertThat("Flow was not deleted",
                   flowManagerHelper.flowsMap.get(flowMatch),
                   nullValue());

    }


    @Test
    public void testIdleExpirationUpdate() throws InterruptedException{
        FlowMatch flowMatch = new FlowMatch().addKey(FlowKeys.tunnelID(10L));

        WildcardMatch wildcardMatch = WildcardMatch.fromFlowMatch(flowMatch);
        WildcardFlowBuilder wildcardFlowBuilder = WildcardFlowBuilder.empty()
            .setMatch(wildcardMatch)
            .setIdleExpirationMillis(timeOut);
        Flow flow = new Flow().setMatch(flowMatch).
                setActions(actionsAsJava(wildcardFlowBuilder));

        long time1 = System.currentTimeMillis();

        int numberOfFlowsAdded = 0;
        WildcardFlow wflow = wildcardFlowBuilder.build();
        flowManager.add(wflow);
        flowManager.add(flow, wflow);
        flowManagerHelper.addFlow(new Flow().setMatch(flowMatch));
        numberOfFlowsAdded++;

        Thread.sleep(timeOut/2);

        // add another flow that matches, that will update the LastUsedTime of a
        // value > timeOut/2
        FlowMatch flowMatch1 = new FlowMatch().addKey(FlowKeys.tunnelID(10L))
                                      .addKey(FlowKeys.tcp(1000, 1002));
        Flow flow2 = new Flow().setActions(actionsAsJava(wflow)).
                                setMatch(flowMatch1);
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
                   flowManager.getDpFlowToWildFlow().size(),
                   equalTo(numberOfFlowsAdded));


        assertThat("DpFlowToWildFlow table was not updated",
                   flowManager.getWildcardTables().get(
                       wflow.getMatch().getUsedFields())
                              .get(wflow.getMatch()),
                   equalTo(wflow));

        long time2 = System.currentTimeMillis();
        // this test is very sensitive to time, it's better to calibrate the
        // sleep according to the speed of the operations
        long sleepTime = timeOut - (time2-time1) + 1;
        if (sleepTime < 0) {
            throw new RuntimeException(
                "This machine is too slow, increase timeout!");
        }
        Thread.sleep(sleepTime);
        // this call will check the flow expiration and if a flow has timeLived >
        // idle-timeout/2, an update about the LastUsedTime will be requested
        // from the kernel
        flowManager.checkFlowsExpiration();

        // wildcard flow should still be there
        assertThat("DpFlowToWildFlow table was not updated",
                   flowManager.getWildcardTables().get(
                                   wflow.getMatch().getUsedFields())
                              .get(wflow.getMatch()),
                   equalTo(wflow));

        Thread.sleep(timeOut);

        flowManager.checkFlowsExpiration();
        // both should be deleted
        assertThat("Flow was not deleted",
                   flowManagerHelper.flowsMap.get(flowMatch),
                   nullValue());
        assertThat("Flow was not deleted",
                   flowManagerHelper.flowsMap.get(flowMatch),
                   nullValue());

        assertThat("Wildcard flow wasn't deleted",
                   flowManager.getWildcardTables().size(),
                   equalTo(0));

    }

    @Test
    public void wildcardFlowUpdatedBecauseOfKernelFlowUpdated()
            throws InterruptedException {
        FlowMatch flowMatch = new FlowMatch().addKey(FlowKeys.tunnelID(10L));

        WildcardMatch wildcardMatch = WildcardMatch.fromFlowMatch(flowMatch);
        WildcardFlowBuilder wildcardFlowBuilder = WildcardFlowBuilder.empty()
            .setMatch(wildcardMatch)
            .setIdleExpirationMillis(timeOut);
        Flow flow = new Flow().setActions(actionsAsJava(wildcardFlowBuilder)).
                               setMatch(flowMatch);

        long time1 = System.currentTimeMillis();

        WildcardFlow wflow = wildcardFlowBuilder.build();
        flowManager.add(wflow);
        flowManager.add(flow, wflow);
        flowManagerHelper.addFlow(new Flow().setMatch(flowMatch));

        Thread.sleep(5*timeOut/8);

        // update the flow LastUsedTime in the kernel (FlowManagerHelperImpl is
        // mocking the kernel in this test)
        flowManagerHelper.setLastUsedTimeToNow(flowMatch);
        flowManager.checkFlowsExpiration();

        Thread.sleep(timeOut/8);

        assertThat("Wildcard flow LastUsedTime was not updated",
                   flowManager.getDpFlowToWildFlow().get(flowMatch).getLastUsedTimeMillis(),
                   equalTo(flowManagerHelper.flowsMap.get(flowMatch)
                                            .getLastUsedTime()));

        long time2 = System.currentTimeMillis();

        long sleepTime = timeOut - (time2-time1) + 1;
        if (sleepTime < 0) {
            throw new RuntimeException(
                "This machine is too slow, increase timeout!");
        }
        // Since the timeLived > timeout/2 the FlowManager will request an update
        // of the LastUsedTime of this flow
        flowManager.checkFlowsExpiration();

        // wildcard flow should still be there
        assertThat("DpFlowToWildFlow table was not updated",
                   flowManager.getWildcardTables().get(
                                   wflow.getMatch().getUsedFields())
                              .get(wflow.getMatch()),
                   equalTo(wflow));

        Thread.sleep(timeOut);

        flowManager.checkFlowsExpiration();
        // both should be deleted
        assertThat("Flow was not deleted",
                   flowManagerHelper.flowsMap.get(flowMatch),
                   nullValue());

        assertThat("Wildcard flow wasn't deleted",
                   flowManager.getWildcardTables().size(),
                   equalTo(0));
    }

    @Test
    public void testFreeSpaceDpTable(){
        int maxAcceptedDpFlows = (int) (maxDpFlowSize - dpFlowRemoveBatchSize);
        // fill the table with a number of flow > maxAcceptedDpFlows
        for (int i=0; i<=maxAcceptedDpFlows; i++) {
            FlowMatch flowMatch =
                new FlowMatch().addKey(FlowKeys.tunnelID(i+1));
            WildcardMatch wildcardMatch =
                WildcardMatch.fromFlowMatch(flowMatch);
            // no time out set
            WildcardFlowBuilder wildcardFlow = WildcardFlowBuilder.empty()
                .setMatch(wildcardMatch);
            Flow flow = new Flow().setActions(actionsAsJava(wildcardFlow))
                                  .setMatch(flowMatch);
            flowManager.add(wildcardFlow.build());
            flowManager.add(flow, wildcardFlow.build());
            flowManagerHelper.addFlow(flow);
        }
        flowManager.checkFlowsExpiration();

        assertThat("DpFlowTable, a flow hasn't been removed",
                   flowManager.getDpFlowTable().size(),
                   equalTo(maxAcceptedDpFlows));

        assertThat("DpFlowToWildFlow, a flow hasn't been removed",
                   flowManager.getDpFlowToWildFlow().size(),
                   equalTo(maxAcceptedDpFlows));

    }

    @Test
    public void testMaximumWildcardFlows() {
        int testSize = 6;
        List<WildcardFlowBuilder> flows = new ArrayList<WildcardFlowBuilder>(testSize);

        for (int counter = 0; counter < testSize; counter++) {
            FlowMatch flowMatch = new FlowMatch().addKey(FlowKeys.tunnelID(counter * 10L));
            WildcardMatch wildcardMatch = WildcardMatch.fromFlowMatch(flowMatch);
            flows.add(WildcardFlowBuilder.empty().setMatch(wildcardMatch));

        }

        assertThat("FlowManager didn't accept the first wildcard flow", flowManager.add(flows.get(0).build()));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(1));
        assertThat("FlowManager didn't accept the second wildcard flow", flowManager.add(flows.get(1).build()));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(2));
        assertThat("FlowManager didn't accept the third wildcard flow", flowManager.add(flows.get(2).build()));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(3));
        assertThat("FlowManager didn't accept the fourth wildcard flow", flowManager.add(flows.get(3).build()));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(4));
        assertThat("FlowManager didn't accept the fifth wildcard flow", flowManager.add(flows.get(4).build()));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(5));
        assertThat("FlowManager didn't reject the last wildcard flow", !flowManager.add(flows.get(5).build()));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(5));
    }

    @Test
    public void testMaximumWildcardFlowsWithExpiration() throws InterruptedException {
        int testSize = 6;
        List<WildcardFlowBuilder> flows = new ArrayList<WildcardFlowBuilder>(testSize);

        for (int counter = 0; counter < testSize; counter++) {
            FlowMatch flowMatch = new FlowMatch().addKey(FlowKeys.tunnelID(counter * 10L));
            WildcardMatch wildcardMatch = WildcardMatch.fromFlowMatch(flowMatch);
            flows.add(WildcardFlowBuilder.empty()
                    .setMatch(wildcardMatch)
                    .setHardExpirationMillis(timeOut));
        }

        assertThat("FlowManager didn't accept the first wildcard flow", flowManager.add(flows.get(0).build()));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(1));
        assertThat("FlowManager didn't accept the second wildcard flow", flowManager.add(flows.get(1).build()));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(2));
        assertThat("FlowManager didn't accept the third wildcard flow", flowManager.add(flows.get(2).build()));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(3));
        assertThat("FlowManager didn't accept the fourth wildcard flow", flowManager.add(flows.get(3).build()));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(4));
        assertThat("FlowManager didn't accept the fifth wildcard flow", flowManager.add(flows.get(4).build()));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(5));

        Thread.sleep(timeOut);
        // all wildcardflows should have been expired now, but they will be there.
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(5));
        // when adding a new wildcard flow it will clean the expired flows.
        assertThat("FlowManager didn't accept the new wildcard flow", flowManager.add(flows.get(5).build()));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(5));
    }

    private List<FlowAction<?>>actionsAsJava(WildcardFlowBase wflow) {
        return new JavaConversions.SeqWrapper<FlowAction<?>>(wflow.actions());
    }

    // Implementation of the FlowManagerHelper for this test
    class FlowManagerHelperImpl implements FlowManagerHelper {

        public Map<FlowMatch, Flow> flowsMap = new HashMap<FlowMatch, Flow>();

        public void addFlow(Flow flow){
            flow.setLastUsedTime(System.currentTimeMillis());
            flowsMap.put(flow.getMatch(), flow);
        }

        public void setLastUsedTimeToNow(FlowMatch match) {
            flowsMap.get(match).setLastUsedTime(System.currentTimeMillis());
        }

        @Override
        public void getFlow(FlowMatch flowMatch) {
            flowManager.updateFlowLastUsedTimeCompleted(flowsMap.get(flowMatch));
        }

        @Override
        public void removeFlow(Flow flow) {
            flowsMap.remove(flow.getMatch());
            flowManager.removeFlowCompleted(flow);
        }

        @Override
        public void removeWildcardFlow(WildcardFlow flow) {
            flowManager.remove(flow);
        }
    }
}
