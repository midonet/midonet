/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.sdn.flows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;
import org.testng.annotations.BeforeTest;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsNull.nullValue;

import com.midokura.sdn.dp.Flow;
import com.midokura.sdn.dp.FlowMatch;
import com.midokura.sdn.dp.flows.FlowKey;
import com.midokura.sdn.dp.flows.FlowKeyTunnelID;

public class FlowManagerTest {

    long maxDpFlowSize = 5;
    int dpFlowRemoveBatchSize = 2;
    FlowManagerHelperImpl flowManagerHelper;
    FlowManager flowManager;

    @BeforeTest
    public void setUp() {
        flowManagerHelper = new FlowManagerHelperImpl();
        flowManager = new FlowManager(flowManagerHelper, maxDpFlowSize,
                                                  dpFlowRemoveBatchSize);
    }

    @Test
    public void testHardTimeExpiration() throws InterruptedException {

        List<FlowKey<?>> keys = new java.util.ArrayList<FlowKey<?>>();
        FlowKeyTunnelID tunnelKey  = new FlowKeyTunnelID().setTunnelID(10l);
        keys.add(tunnelKey);

        FlowMatch flowMatch = new FlowMatch().setKeys(keys);

        WildcardMatch wildcardMatch = WildcardMatches.fromFlowMatch(flowMatch);

        WildcardFlow wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
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

        List<FlowKey<?>> keys = new java.util.ArrayList<FlowKey<?>>();
        FlowKeyTunnelID tunnelKey  = new FlowKeyTunnelID().setTunnelID(10l);
        keys.add(tunnelKey);

        FlowMatch flowMatch = new FlowMatch().setKeys(keys);

        WildcardMatch wildcardMatch = WildcardMatches.fromFlowMatch(flowMatch);

        WildcardFlow wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
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

        Thread.sleep(10);
        keys.clear();

        // add another flow that matches


        flowManager.checkFlowsExpiration();

        assertThat("Flow was not deleted",
                   flowManagerHelper.getFlow(flowMatch),
                   nullValue());


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
        public void removeFlow(Flow flow) {
            flowsMap.remove(flow.getMatch());
            flowManager.getFlowDeleteCallback(flow).onSuccess(flow);

        }

        @Override
        public void removeWildcardFlow(WildcardFlow flow) {

            Set<FlowMatch> matchSet = flowManager.remove(flow);
            for( FlowMatch match: matchSet){
                removeFlow(new Flow().setMatch(match));
            }

        }
    }
}
