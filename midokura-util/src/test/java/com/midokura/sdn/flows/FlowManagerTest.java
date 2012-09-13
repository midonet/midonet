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

import com.midokura.netlink.Callback;
import com.midokura.sdn.dp.Flow;
import com.midokura.sdn.dp.FlowMatch;
import com.midokura.sdn.dp.flows.FlowAction;
import com.midokura.sdn.dp.flows.FlowKeys;

public class FlowManagerTest {

    long maxDpFlowSize = 5;
    int dpFlowRemoveBatchSize = 2;
    FlowManagerHelperImpl flowManagerHelper;
    FlowManager flowManager;

    @Before
    public void setUp() {
        flowManagerHelper = new FlowManagerHelperImpl();
        flowManager = new FlowManager(flowManagerHelper, maxDpFlowSize,
                                                  dpFlowRemoveBatchSize);
    }

    @Test
    public void testHardTimeExpiration() throws InterruptedException {

        FlowMatch flowMatch = new FlowMatch().addKey(FlowKeys.tunnelID(10l));

        WildcardMatch wildcardMatch = WildcardMatches.fromFlowMatch(flowMatch);

        WildcardFlow wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .setActions(new ArrayList<FlowAction<?>>())
            .setHardExpirationMillis(20);
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
                   flowManager.getWildcardTables().get(wildcardFlow.getMatch().getUsedFields())
                              .get(wildcardFlow.getMatch()),
                   equalTo(wildcardFlow));

        Thread.sleep(20);

        flowManager.checkFlowsExpiration();

        assertThat("Flow was not deleted",
                   flowManagerHelper.getFlow(flowMatch),
                   nullValue());

    }

    @Test
    public void testIdleExpiration() throws InterruptedException {

        FlowMatch flowMatch = new FlowMatch().addKey(FlowKeys.tunnelID(10l));

        WildcardMatch wildcardMatch = WildcardMatches.fromFlowMatch(flowMatch);

        WildcardFlow wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .setActions(new ArrayList<FlowAction<?>>())
            .setIdleExpirationMillis(20);
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
                   flowManager.getWildcardTables().get(wildcardFlow.getMatch().getUsedFields())
                              .get(wildcardFlow.getMatch()),
                   equalTo(wildcardFlow));

        Thread.sleep(21);

        flowManager.checkFlowsExpiration();

        assertThat("Flow was not deleted",
                   flowManagerHelper.getFlow(flowMatch),
                   nullValue());

    }

    @Test
    public void testIdleExpirationUpdate() throws InterruptedException{

        FlowMatch flowMatch = new FlowMatch().addKey(FlowKeys.tunnelID(10l));

        WildcardMatch wildcardMatch = WildcardMatches.fromFlowMatch(flowMatch);
        WildcardFlow wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .setIdleExpirationMillis(20)
            .setActions(new ArrayList<FlowAction<?>>());

        int numberOfFlowsAdded = 0;
        flowManager.add(wildcardFlow);
        flowManager.add(flowMatch, wildcardFlow);
        flowManagerHelper.addFlow(new Flow().setMatch(flowMatch));
        numberOfFlowsAdded++;

        Thread.sleep(10);

        // add another flow that matches
        FlowMatch flowMatch1 = new FlowMatch().addKey(FlowKeys.tunnelID(10l))
                                      .addKey(FlowKeys.tcp(1000,1002));
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
                   flowManager.getWildcardTables().get(wildcardFlow.getMatch().getUsedFields())
                              .get(wildcardFlow.getMatch()),
                   equalTo(wildcardFlow));

        Thread.sleep(12);

        flowManager.checkFlowsExpiration();


        // wildcard flow should still be there
        assertThat("DpFlowToWildFlow table was not updated",
                   flowManager.getWildcardTables().get(wildcardFlow.getMatch().getUsedFields())
                              .get(wildcardFlow.getMatch()),
                   equalTo(wildcardFlow));

        Thread.sleep(21);

        flowManager.checkFlowsExpiration();
        // both should be deleted
        assertThat("Flow was not deleted",
                   flowManagerHelper.getFlow(flowMatch),
                   nullValue());
        assertThat("Flow was not deleted",
                   flowManagerHelper.getFlow(flowMatch1),
                   nullValue());

        assertThat("Wildcard flow wasn't deleted",
                   flowManager.getWildcardTables().size(),
                   equalTo(0));

    }

    @Test
    public void testFreeSpaceDpTable(){
        int maxAcceptedDpFlows = (int) (maxDpFlowSize - dpFlowRemoveBatchSize);

        for(int i=0; i<=maxAcceptedDpFlows; i++){

            FlowMatch flowMatch = new FlowMatch().addKey(FlowKeys.tunnelID(i+1));

            WildcardMatch wildcardMatch = WildcardMatches.fromFlowMatch(flowMatch);
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
                   equalTo(maxAcceptedDpFlows-1));

        assertThat("WildcardFlowsToDpFlows, a flow hasn't been removed",
                   flowManager.getWildFlowToDpFlows().size(),
                   equalTo(maxAcceptedDpFlows-1));

        assertThat("DpFlowToWildFlow, a flow hasn't been removed",
                   flowManager.getDpFlowToWildFlow().size(),
                   equalTo(maxAcceptedDpFlows-1));

    }

    class FlowManagerHelperImpl implements FlowManagerHelper {

        Map<FlowMatch, Flow> flowsMap = new HashMap<FlowMatch, Flow>();

        public void addFlow(Flow flow){
            flowsMap.put(flow.getMatch(), flow);
            flowManager.getFlowCreatedCallback(flow).onSuccess(flow);
        }

        @Override
        public Flow getFlow(FlowMatch flowMatch) {
            return flowsMap.get(flowMatch);
        }

        @Override
        public void removeFlow(Flow flow, Callback<Flow> cb) {
            flowsMap.remove(flow.getMatch());
            cb.onSuccess(flow);
        }

        @Override
        public void removeWildcardFlow(WildcardFlow flow) {
            flowManager.remove(flow);
        }
    }
}
