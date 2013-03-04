/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.sdn.flows;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import static java.util.EnumSet.of;

import org.junit.Test;

import org.midonet.odp.FlowMatch;
import org.midonet.odp.FlowMatches;


import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import static org.midonet.odp.FlowMatches.tcpFlow;
import static org.midonet.sdn.flows.WildcardMatch.Field.EthernetDestination;
import static org.midonet.sdn.flows.WildcardMatch.Field.EthernetSource;

public class TestWildcardMatch {

    @Test
    public void testEqualityRelationByProjection() {

        WildcardMatch wildcard =
            WildcardMatch.fromFlowMatch(
                tcpFlow("ae:b3:77:8c:a1:48", "33:33:00:00:00:16",
                        "192.168.100.1", "192.168.100.2",
                        8096, 1025));

        WildcardMatch projection = new ProjectedWildcardMatch(
                of(EthernetSource, EthernetDestination), wildcard);

        assertThat("A wildcard should not match a projection smaller than it",
                   wildcard, not(equalTo(projection)));

        assertThat("A project should be equal to a wildcard bigger than it.",
                   projection, equalTo(wildcard));
    }

    @Test
    public void testFindableInMap() {
        WildcardMatch wildcard =
            WildcardMatch.fromFlowMatch(
                tcpFlow("ae:b3:77:8c:a1:48", "33:33:00:00:00:16",
                        "192.168.100.1", "192.168.100.2",
                        8096, 1025));

        WildcardMatch projection =
            wildcard.project(EnumSet.of(EthernetSource, EthernetDestination));

        // make a simple wildcard that is a copy of the projection
        WildcardMatch copy = new WildcardMatch();
        copy.setEthernetDestination(projection.getEthernetDestination());
        copy.setEthernetSource(projection.getEthernetSource());

        Map<WildcardMatch, Boolean> map = new HashMap<WildcardMatch, Boolean>();
        map.put(copy, Boolean.TRUE);

        assertThat(
            "We should be able to retrieve a wildcard flow by projection",
            map.get(projection), is(notNullValue()));

        assertThat(
            "We should be able to retrieve a wildcard flow by projection",
            map.get(projection), is(true));
    }

    @Test
    public void testFromFlowMatch() {
        FlowMatch fm = FlowMatches.tcpFlow(
            "02:aa:dd:dd:aa:01", "02:bb:ee:ee:ff:01",
            "192.168.100.2", "192.168.100.3",
            40000, 50000);
        WildcardMatch wcm = WildcardMatch.fromFlowMatch(fm);
        assertThat(wcm.getTransportSourceObject(),
            equalTo(40000));
        assertThat(wcm.getTransportDestinationObject(),
            equalTo(50000));

    }
}
