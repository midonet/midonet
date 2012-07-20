/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.protos;

import java.util.List;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

import com.midokura.util.netlink.dp.Datapath;
import com.midokura.util.netlink.dp.Flow;
import com.midokura.util.netlink.dp.FlowMatch;
import com.midokura.util.netlink.dp.flows.FlowAction;

public abstract class OvsFlowsCreateSetSomeTest
    extends AbstractNetlinkProtocolTest<OvsDatapathConnection> {

    private static final Logger log = LoggerFactory
        .getLogger(OvsFlowsCreateSetSomeTest.class);

    protected void setUp(final byte[][] responses) throws Exception {
        super.setUp(responses);
        connection = OvsDatapathConnection.create(channel, reactor);
    }

    public void doTest() throws Exception {

        connection.initialize();
        // fire reply
        fireReply(6);

        Future<Datapath> dpFuture = connection.datapathsGet("bibi");
        fireReply();
        Datapath datapath = dpFuture.get();

        Future<Flow> flowFuture =
            connection.flowsCreate(dpFuture.get(),
                                   new Flow().setMatch(flowMatch()));

        fireReply();
        assertThat("The returned flow has the same Match as we wanted",
                   flowFuture.get().getMatch(), equalTo(flowMatch()));

        Future<Flow> retrievedFlowFuture =
            connection.flowsGet(datapath, flowMatch());
        fireReply();
        fireReply();
        assertThat("The retrieved flow has the same Match as we wanted",
                   retrievedFlowFuture.get().getMatch(), equalTo(flowMatch()));

        // update the with actions.
        Flow updatedFlow =
            new Flow()
                .setMatch(flowMatch())
                .setActions(flowActions());
//                userspace()

        Future<Flow> flowWithActionsFuture = connection.flowsSet(datapath,
                                                                 updatedFlow);
        fireReply();

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
