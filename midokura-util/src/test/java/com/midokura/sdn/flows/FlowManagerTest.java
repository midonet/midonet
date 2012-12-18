/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.sdn.flows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Ignore;
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
    long maxWildcardFlowSize = 5;
    int dpFlowRemoveBatchSize = 2;
    FlowManagerHelperImpl flowManagerHelper;
    FlowManager flowManager;
    long timeOut = 4000;

    @Before
    public void setUp() {
        flowManagerHelper = new FlowManagerHelperImpl();
        flowManager = new FlowManager(flowManagerHelper, maxDpFlowSize,
                                                  maxWildcardFlowSize, dpFlowRemoveBatchSize);
    }


    @Test
    public void testHardTimeExpiration() throws InterruptedException {

        FlowMatch flowMatch = new FlowMatch().addKey(FlowKeys.tunnelID(10L));

        WildcardMatch wildcardMatch = WildcardMatches.fromFlowMatch(flowMatch);

        WildcardFlow wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .setActions(new ArrayList<FlowAction<?>>())
            .setHardExpirationMillis(timeOut);
        int numberOfFlowsAdded = 0;
        flowManager.add(wildcardFlow);
        flowManager.add(flowMatch, wildcardFlow);
        // add flow
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
        // the flow should be expired since timeLived > timeOut
        flowManager.checkFlowsExpiration();

        assertThat("Flow was not deleted",
                   flowManagerHelper.flowsMap.get(flowMatch),
                   nullValue());

    }

    @Test
    public void testIdleExpiration() throws InterruptedException {
        FlowMatch flowMatch = new FlowMatch().addKey(FlowKeys.tunnelID(10L));

        WildcardMatch wildcardMatch = WildcardMatches.fromFlowMatch(flowMatch);

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

        // the flow should be expired since lastUsedTime > timeOut
        flowManager.checkFlowsExpiration();

        assertThat("Flow was not deleted",
                   flowManagerHelper.flowsMap.get(flowMatch),
                   nullValue());

    }


    @Test
    public void testIdleExpirationUpdate() throws InterruptedException{
        FlowMatch flowMatch = new FlowMatch().addKey(FlowKeys.tunnelID(10L));

        WildcardMatch wildcardMatch = WildcardMatches.fromFlowMatch(flowMatch);
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

        // add another flow that matches, that will update the LastUsedTime of a
        // value > timeOut/2
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
        // this call will check the flow expiration and if a flow has timeLived >
        // idle-timeout/2, an update about the LastUsedTime will be requested
        // from the kernel
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

        WildcardMatch wildcardMatch = WildcardMatches.fromFlowMatch(flowMatch);
        WildcardFlow wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .setIdleExpirationMillis(timeOut)
            .setActions(new ArrayList<FlowAction<?>>());

        long time1 = System.currentTimeMillis();

        flowManager.add(wildcardFlow);
        flowManager.add(flowMatch, wildcardFlow);
        flowManagerHelper.addFlow(new Flow().setMatch(flowMatch));

        Thread.sleep(5*timeOut/8);

        // update the flow LastUsedTime in the kernel (FlowManagerHelperImpl is
        // mocking the kernel in this test)
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
        // Since the timeLived > timeout/2 the FlowManager will request an update
        // of the LastUsedTime of this flow
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
        // fill the table with a number of flow > maxAcceptedDpFlows
        for (int i=0; i<=maxAcceptedDpFlows; i++) {
            FlowMatch flowMatch =
                new FlowMatch().addKey(FlowKeys.tunnelID(i+1));
            WildcardMatch wildcardMatch =
                WildcardMatches.fromFlowMatch(flowMatch);
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

        assertThat("DpFlowToWildFlow, a flow hasn't been removed",
                   flowManager.getDpFlowToWildFlow().size(),
                   equalTo(maxAcceptedDpFlows));

    }

    @Test
    public void testMaximumWildcardFlows() {
        int testSize = 6;
        List<WildcardFlow> flows = new ArrayList<WildcardFlow>(testSize);

        for (int counter = 0; counter < testSize; counter++) {
            FlowMatch flowMatch = new FlowMatch().addKey(FlowKeys.tunnelID(counter * 10L));
            WildcardMatch wildcardMatch = WildcardMatches.fromFlowMatch(flowMatch);
            flows.add(new WildcardFlow().setMatch(wildcardMatch).setActions(new ArrayList<FlowAction<?>>()));

        }

        assertThat("FlowManager didn't accept the first wildcard flow", flowManager.add(flows.get(0)));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(1l));
        assertThat("FlowManager didn't accept the second wildcard flow", flowManager.add(flows.get(1)));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(2l));
        assertThat("FlowManager didn't accept the third wildcard flow", flowManager.add(flows.get(2)));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(3l));
        assertThat("FlowManager didn't accept the fourth wildcard flow", flowManager.add(flows.get(3)));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(4l));
        assertThat("FlowManager didn't accept the fifth wildcard flow", flowManager.add(flows.get(4)));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(5l));
        assertThat("FlowManager didn't reject the last wildcard flow", !flowManager.add(flows.get(5)));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(5l));
    }

    @Test
    public void testMaximumWildcardFlowsWithExpiration() throws InterruptedException {
        int testSize = 6;
        List<WildcardFlow> flows = new ArrayList<WildcardFlow>(testSize);

        for (int counter = 0; counter < testSize; counter++) {
            FlowMatch flowMatch = new FlowMatch().addKey(FlowKeys.tunnelID(counter * 10L));
            WildcardMatch wildcardMatch = WildcardMatches.fromFlowMatch(flowMatch);
            flows.add(new WildcardFlow()
                    .setMatch(wildcardMatch)
                    .setActions(new ArrayList<FlowAction<?>>())
                    .setHardExpirationMillis(timeOut));
        }

        assertThat("FlowManager didn't accept the first wildcard flow", flowManager.add(flows.get(0)));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(1l));
        assertThat("FlowManager didn't accept the second wildcard flow", flowManager.add(flows.get(1)));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(2l));
        assertThat("FlowManager didn't accept the third wildcard flow", flowManager.add(flows.get(2)));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(3l));
        assertThat("FlowManager didn't accept the fourth wildcard flow", flowManager.add(flows.get(3)));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(4l));
        assertThat("FlowManager didn't accept the fifth wildcard flow", flowManager.add(flows.get(4)));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(5l));

        Thread.sleep(timeOut);
        // all wildcardflows should have been expired now, but they will be there.
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(5l));
        // when adding a new wildcard flow it will clean the expired flows.
        assertThat("FlowManager didn't accept the new wildcard flow", flowManager.add(flows.get(5)));
        assertThat("Table size is incorrect", flowManager.getNumWildcardFlows(), equalTo(5l));
    }

    // Implementation of the FlowManagerHelper for this test
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
