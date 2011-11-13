package com.midokura.midonet.smoketest;

import static org.junit.Assert.assertEquals;

import java.net.URI;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dto.MaterializedRouterPort;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.data.dto.Tenant;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;
import com.midokura.midonet.smoketest.mocks.MockMidolmanMgmt;

public class SmokeTest {

    private final static Logger log = LoggerFactory.getLogger(SmokeTest.class);

    @Test
    public void test() {
        // 1) Create mock objects:
        // - smoketest.mocks.MockMidolmanManagement
        // - ?
        // Init directory.
        MidolmanMgmt mgmt = new MockMidolmanMgmt();
        // Add the tenant
        Tenant tenant = new Tenant();
        tenant.setId("tenant1");
        URI tenantURI = mgmt.addTenant(tenant);
        log.debug("tenant location: {}", tenantURI);
        // Add a router.
        Router router = new Router();
        String routerName = "router1";
        router.setName(routerName);
        URI routerURI = mgmt.addRouter(tenantURI, router);
        log.debug("router location: {}", routerURI);
        // Get the router.
        router = mgmt.get(routerURI.getPath(), Router.class);
        log.debug("router name: {}", router.getName());
        assertEquals(router.getName(), routerName);

        // Add a materialized router port.
        MaterializedRouterPort port = new MaterializedRouterPort();
        String portAddress = "180.214.47.66";
        port.setNetworkAddress("180.214.47.64");
        port.setNetworkLength(30);
        port.setPortAddress(portAddress);
        port.setLocalNetworkAddress("180.214.47.64");
        port.setLocalNetworkLength(30);
        log.debug("port JSON {}", port.toString());
        URI portURI = mgmt.addRouterPort(routerURI, port);
        log.debug("port location: {}", portURI);
        // Get the port.
        port = mgmt.get(portURI.getPath(), MaterializedRouterPort.class);
        log.debug("port address: {}", port.getPortAddress());
        assertEquals(port.getPortAddress(), portAddress);

        // 2) Make REST calls to create the virtual topology.
        // See midolmanj-mgmt:
        // com.midokura.midolman.mgmt.rest_api.v1.MainTest.java
        // and com.midokura.midolman.mgmt.tools.CreateZkTestConfig

        // 3) Create taps

        // 4) Create OVS datapaths and ports and set externalIds of
        // corresponding
        // vports. Use midolmanj's com.midokura.midolman.openvswitch.
        // OpenvSwitchDatabaseConnectionImpl class (it's defined in scala code).

        // 5) Create a ControllerTrampoline for each datapath. Initially, just
        // one
        // later probably 2. Later modify midolmanj's com.midokura.midolman
        // package to allow different ControllerTrampolines listening on
        // different ports.

        // 6) Ping a port.

        // 7) Tear down the OVS structures and taps.
    }

}
