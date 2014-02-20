/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.cluster;

import org.junit.Test;
import org.midonet.cluster.data.Route;
import org.midonet.cluster.data.Router;
import org.midonet.cluster.data.ports.RouterPort;
import org.midonet.midolman.layer3.Route.NextHop;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;


public class LocalDataClientImplTest extends LocalDataClientImplTestBase {

    @Test
    public void routerPortLifecycleTest() throws StateAccessException,
            SerializationException {
        // Create a materialized router port.
        UUID routerId = client.routersCreate(new Router());
        UUID portId = client.portsCreate(
            new RouterPort().setDeviceId(routerId)
                .setHwAddr(MAC.fromString("02:BB:EE:EE:FF:01"))
                .setPortAddr("10.0.0.3").setNwAddr("10.0.0.0")
                .setNwLength(24)
        );
        // Verify that this automatically creates one route.
        List<Route> routes = client.routesFindByRouter(routerId);
        assertThat(routes, hasSize(1));
        Route rt = routes.get(0);
        // Verify that the route is type LOCAL and forwards to the new port.
        assertThat(rt.getNextHop(), equalTo(NextHop.LOCAL));
        assertThat(rt.getNextHopPort(), equalTo(portId));
        assertThat(rt.getNextHopGateway(), equalTo(
            IPv4Addr.intToString(
                org.midonet.midolman.layer3.Route.NO_GATEWAY)));
        // Now delete the port and verify that the route is deleted.
        client.portsDelete(portId);
        routes = client.routesFindByRouter(routerId);
        assertThat(routes, hasSize(0));
    }

    @Test
    public void checkHealthMonitorNodeTest() throws StateAccessException {
        Integer hmHost = client.getPrecedingHealthMonitorLeader(14);
        assertThat(hmHost, equalTo(null));

        Integer hostNum1 = client.registerAsHealthMonitorNode();
        hmHost = client.getPrecedingHealthMonitorLeader(1);
        assertThat(hmHost, equalTo(hostNum1));
        assertThat(hostNum1, equalTo(0));

        Integer hostNum2 = client.registerAsHealthMonitorNode();
        hmHost = client.getPrecedingHealthMonitorLeader(6);
        assertThat(hmHost, equalTo(hostNum2));
        assertThat(hostNum2, equalTo(1));

        Integer hostNum3 = client.registerAsHealthMonitorNode();
        hmHost = client.getPrecedingHealthMonitorLeader(1);
        assertThat(hmHost, equalTo(hostNum1));
        assertThat(hostNum3, equalTo(2));
    }
}
