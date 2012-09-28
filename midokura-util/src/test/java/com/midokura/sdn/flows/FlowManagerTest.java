/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.sdn.flows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.IsNull.nullValue;

import com.midokura.sdn.dp.Flow;
import com.midokura.sdn.dp.FlowMatch;
import com.midokura.sdn.dp.flows.FlowAction;
import com.midokura.sdn.dp.flows.FlowKeys;

public class FlowManagerTest {

    long maxDpFlowSize = 5;
    int dpFlowRemoveBatchSize = 2;
    FlowManagerHelperImpl flowManagerHelper;
    FlowManager flowManager;
    long timeOut = 4000;

    @Before
    public void setUp() {
        flowManagerHelper = new FlowManagerHelperImpl();
        flowManager = new FlowManager(flowManagerHelper, maxDpFlowSize,
                                                  dpFlowRemoveBatchSize);
    }


    @Test
    public void testHardTimeExpiration() throws InterruptedException {

        FlowMatch flowMatch = new FlowMatch().addKey(FlowKeys.tunnelID(10L));

        WildcardMatch wildcardMatch = WildcardMatch.fromFlowMatch(flowMatch);

        WildcardFlow wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .setActions(new ArrayList<FlowAction<?>>())
            .setHardExpirationMillis(timeOut);
        int numberOfFlowsAdded = 0;
        flowManager.add(wildcardFlow);
        flowManager.add(flowMatch, wildcardFlow);
        flowManagerHelper.addFlow(new Flow().setMatch(flowMatch));
        numberOfFlowsAdded++;
        assertThat("DpFlowTable was not updated",
                   flowManager.getDpFlowTable().size(),
                   equalTo(numberOfFlowsAdded));

        assertThat("WildcardFlowsToDpFlows was not updated",
                   flowManager.getWildFlowToDpFlows().size(),
                   equalTo(numberOfFlowsAdded));

        assertThat("DpFlowToWildFlow table was not updated",
                   flowManager.getDpFlowToWildFlow().size(),
                   equalTo(numberOfFlowsAdded));


        assertThat("DpFlowToWildFlow table was not updated",
                   flowManager.getWildcardTables().get(
                                   wildcardFlow.getMatch().getUsedFields())
                              .get(wildcardFlow.getMatch()),
                   equalTo(wildcardFlow));

        Thread.sleep(timeOut);

        flowManager.checkFlowsExpiration();

        assertThat("Flow was not deleted",
                   flowManagerHelper.flowsMap.get(flowMatch),
                   nullValue());

    }

    @Test
    public void testIdleExpiration() throws InterruptedException {
        FlowMatch flowMatch = new FlowMatch().addKey(FlowKeys.tunnelID(10L));

        WildcardMatch wildcardMatch = WildcardMatch.fromFlowMatch(flowMatch);

        WildcardFlow wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .setActions(new ArrayList<FlowAction<?>>())
            .setIdleExpirationMillis(timeOut);
        int numberOfFlowsAdded = 0;
        flowManager.add(wildcardFlow);
        flowManager.add(flowMatch, wildcardFlow);
        flowManagerHelper.addFlow(new Flow().setMatch(flowMatch));
        numberOfFlowsAdded++;
        assertThat("DpFlowTable was not updated",
                   flowManager.getDpFlowTable().size(),
                   equalTo(numberOfFlowsAdded));

        assertThat("WildcardFlowsToDpFlows was not updated",
                   flowManager.getWildFlowToDpFlows().size(),
                   equalTo(numberOfFlowsAdded));

        assertThat("DpFlowToWildFlow table was not updated",
                   flowManager.getDpFlowToWildFlow().size(),
                   equalTo(numberOfFlowsAdded));


        assertThat("DpFlowToWildFlow table was not updated",
                   flowManager.getWildcardTables().get(
                                   wildcardFlow.getMatch().getUsedFields())
                              .get(wildcardFlow.getMatch()),
                   equalTo(wildcardFlow));

        Thread.sleep(timeOut+1);

        flowManager.checkFlowsExpiration();

        assertThat("Flow was not deleted",
                   flowManagerHelper.flowsMap.get(flowMatch),
                   nullValue());

    }


    @Test
    public void testIdleExpirationUpdate() throws InterruptedException{
        FlowMatch flowMatch = new FlowMatch().addKey(FlowKeys.tunnelID(10L));

        WildcardMatch wildcardMatch = WildcardMatch.fromFlowMatch(flowMatch);
        WildcardFlow wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .setIdleExpirationMillis(timeOut)
            .setActions(new ArrayList<FlowAction<?>>());

        long time1 = System.currentTimeMillis();

        int numberOfFlowsAdded = 0;
        flowManager.add(wildcardFlow);
        flowManager.add(flowMatch, wildcardFlow);
        flowManagerHelper.addFlow(new Flow().setMatch(flowMatch));
        numberOfFlowsAdded++;

        Thread.sleep(timeOut/2);

        // add another flow that matches
        FlowMatch flowMatch1 = new FlowMatch().addKey(FlowKeys.tunnelID(10L))
                                      .addKey(FlowKeys.tcp(1000, 1002));
        Flow flow2 = flowManager.createDpFlow(flowMatch1);
        assertThat("Flow didn't match", flow2, notNullValue());
        // create the flow
        flowManagerHelper.addFlow(flow2);

        numberOfFlowsAdded++;

        assertThat("DpFlowTable was not updated",
                   flowManager.getDpFlowTable().size(),
                   equalTo(numberOfFlowsAdded));

        assertThat("WildcardFlowsToDpFlows was not updated",
                   flowManager.getWildFlowToDpFlows().get(wildcardFlow).size(),
                   equalTo(numberOfFlowsAdded));

        assertThat("DpFlowToWildFlow table was not updated",
                   flowManager.getDpFlowToWildFlow().size(),
                   equalTo(numberOfFlowsAdded));


        assertThat("DpFlowToWildFlow table was not updated",
                   flowManager.getWildcardTables().get(
                       wildcardFlow.getMatch().getUsedFields())
                              .get(wildcardFlow.getMatch()),
                   equalTo(wildcardFlow));

        long time2 = System.currentTimeMillis();
        // this test is very sensitive to time, it's better to calibrate the
        // sleep according to the speed of the operations
        long sleepTime = timeOut - (time2-time1) + 1;
        if (sleepTime < 0) {
            throw new RuntimeException(
                "This machine is too slow, increase timeout!");
        }
        Thread.sleep(sleepTime);

        flowManager.checkFlowsExpiration();

        // wildcard flow should still be there
        assertThat("DpFlowToWildFlow table was not updated",
                   flowManager.getWildcardTables().get(
                                   wildcardFlow.getMatch().getUsedFields())
                              .get(wildcardFlow.getMatch()),
                   equalTo(wildcardFlow));

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
        WildcardFlow wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .setIdleExpirationMillis(timeOut)
            .setActions(new ArrayList<FlowAction<?>>());

        long time1 = System.currentTimeMillis();

        flowManager.add(wildcardFlow);
        flowManager.add(flowMatch, wildcardFlow);
        flowManagerHelper.addFlow(new Flow().setMatch(flowMatch));

        Thread.sleep(5*timeOut/8);

        // update the flow in the kernel
        flowManagerHelper.setLastUsedTimeToNow(flowMatch);
        flowManager.checkFlowsExpiration();

        Thread.sleep(timeOut/8);

        assertThat("Wildcard flow LastUsedTime was not updated",
                   wildcardFlow.getLastUsedTimeMillis(),
                   equalTo(flowManagerHelper.flowsMap.get(flowMatch)
                                            .getLastUsedTime()));

        long time2 = System.currentTimeMillis();

        long sleepTime = timeOut - (time2-time1) + 1;
        if (sleepTime < 0) {
            throw new RuntimeException(
                "This machine is too slow, increase timeout!");
        }

        flowManager.checkFlowsExpiration();

        // wildcard flow should still be there
        assertThat("DpFlowToWildFlow table was not updated",
                   flowManager.getWildcardTables().get(
                                   wildcardFlow.getMatch().getUsedFields())
                              .get(wildcardFlow.getMatch()),
                   equalTo(wildcardFlow));

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

        for (int i=0; i<=maxAcceptedDpFlows; i++) {
            FlowMatch flowMatch =
                new FlowMatch().addKey(FlowKeys.tunnelID(i+1));
            WildcardMatch wildcardMatch =
                WildcardMatch.fromFlowMatch(flowMatch);
            // no time out set
            WildcardFlow wildcardFlow = new WildcardFlow()
                .setMatch(wildcardMatch)
                .setActions(new ArrayList<FlowAction<?>>());
            flowManager.add(wildcardFlow);
            flowManager.add(flowMatch, wildcardFlow);
            flowManagerHelper.addFlow(new Flow().setMatch(flowMatch));
        }
        flowManager.checkFlowsExpiration();

        assertThat("DpFlowTable, a flow hasn't been removed",
                   flowManager.getDpFlowTable().size(),
                   equalTo(maxAcceptedDpFlows));

        assertThat("WildcardFlowsToDpFlows, a flow hasn't been removed",
                   flowManager.getWildFlowToDpFlows().size(),
                   equalTo(maxAcceptedDpFlows));

        assertThat("DpFlowToWildFlow, a flow hasn't been removed",
                   flowManager.getDpFlowToWildFlow().size(),
                   equalTo(maxAcceptedDpFlows));

    }

    class FlowManagerHelperImpl implements FlowManagerHelper {

        public Map<FlowMatch, Flow> flowsMap = new HashMap<FlowMatch, Flow>();

        public void addFlow(Flow flow){
            flow.setLastUsedTime(System.currentTimeMillis());
            flowsMap.put(flow.getMatch(), flow);
            flowManager.addFlowCompleted(flow);
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
