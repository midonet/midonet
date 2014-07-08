/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp.protos;

import java.util.List;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.CoreMatchers.equalTo;

import org.midonet.odp.Datapath;
import org.midonet.odp.Flow;
import org.midonet.odp.FlowMatch;
import org.midonet.odp.flows.FlowAction;


public abstract class OvsFlowsCreateSetMatchTest
        extends AbstractNetlinkProtocolTest {

    private static final Logger log = LoggerFactory
        .getLogger(OvsFlowsCreateSetMatchTest.class);

    protected void setUp(final byte[][] responses) throws Exception {
        super.setUp(responses);
        setConnection();
        connection.bypassSendQueue(true);
        connection.setMaxBatchIoOps(1);
    }

    public void doTest() throws Exception {

        initializeConnection(connection.futures.initialize(), 6);

        Future<Datapath> dpFuture = connection.futures.datapathsGet("bibi");
        exchangeMessage();
        Datapath datapath = dpFuture.get();

        Future<Flow> flowFuture =
            connection.futures.flowsCreate(dpFuture.get(), new Flow(flowMatch()));

        exchangeMessage();
        assertThat("The returned flow has the same Match as we wanted",
                   flowFuture.get().getMatch(), equalTo(flowMatch()));

        Future<Flow> retrievedFlowFuture =
            connection.futures.flowsGet(datapath, flowMatch());

        exchangeMessage(2);
        assertThat("The retrieved flow has the same Match as we wanted",
                   retrievedFlowFuture.get().getMatch(), equalTo(flowMatch()));

        // update the with actions.
        Flow updatedFlow = new Flow(flowMatch(), flowActions());

        Future<Flow> flowWithActionsFuture = connection.futures.flowsSet(datapath,
                                                                 updatedFlow);
        exchangeMessage();

        assertThat("The updated flow has the same keySet as the requested one",
                   flowWithActionsFuture.get().getMatch(),
                   equalTo(flowMatch()));
        assertThat("The updated flow has the same actionSet we wanted",
                   flowWithActionsFuture.get().getActions(),
                   equalTo(updatedFlow.getActions()));
    }

    protected abstract FlowMatch flowMatch();

    protected abstract List<FlowAction> flowActions();
}
