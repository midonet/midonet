/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.host;

import com.midokura.midolman.host.state.HostZkManager;
import com.midokura.midolman.mgmt.VendorMediaType;
import com.midokura.midolman.mgmt.host.rest_api.HostTopology;
import com.midokura.midolman.mgmt.rest_api.DtoWebResource;
import com.midokura.midolman.mgmt.rest_api.FuncTest;
import com.midokura.midolman.mgmt.rest_api.Topology;
import com.midokura.midolman.mgmt.zookeeper.StaticMockDirectory;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.client.dto.DtoBridge;
import com.midokura.midonet.client.dto.DtoBridgePort;
import com.midokura.midonet.client.dto.DtoHost;
import com.midokura.midonet.client.dto.DtoHostInterfacePort;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;
import junit.framework.Assert;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

import java.util.UUID;

@RunWith(Enclosed.class)
public class TestHostInterfacePort {

    public static final String ZK_ROOT_MIDOLMAN = "/test/midolman";

    public static class TestCrud extends JerseyTest {

        private DtoWebResource dtoResource;
        private Topology topology;
        private HostTopology hostTopology;
        private HostZkManager hostManager;
        private Directory rootDirectory;

        private UUID host1Id = UUID.randomUUID();

        public TestCrud() {
            super(FuncTest.appDesc);
        }

        @Before
        public void setUp() throws StateAccessException {

            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);
            rootDirectory = StaticMockDirectory.getDirectoryInstance();
            hostManager = new HostZkManager(rootDirectory, ZK_ROOT_MIDOLMAN);

            DtoHost host1 = new DtoHost();
            host1.setName("host1");

            DtoBridge bridge1 = new DtoBridge();
            bridge1.setName("bridge1-name");
            bridge1.setTenantId("tenant1-id");

            DtoBridgePort bridgePort1 = new DtoBridgePort();

            topology = new Topology.Builder(dtoResource)
                    .create("bridge1", bridge1)
                    .create("bridge1", "bridgePort1", bridgePort1)
                    .build();
            hostTopology = new HostTopology.Builder(dtoResource, hostManager)
                    .create(host1Id, host1).build();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Test
        public void testCrud() throws Exception {

            DtoHost host = hostTopology.getHost(host1Id);
            DtoBridgePort port1 = topology.getMatBridgePort("bridgePort1");

            // List mappings.  There should be none.
            DtoHostInterfacePort[] maps = dtoResource.getAndVerifyOk(
                    host.getPorts(),
                    VendorMediaType
                            .APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON,
                    DtoHostInterfacePort[].class);
            Assert.assertEquals(0, maps.length);

            // Map a tunnel zone to a host
            DtoHostInterfacePort mapping = new DtoHostInterfacePort();
            mapping.setPortId(port1.getId());
            mapping.setInterfaceName("eth0");
            mapping = dtoResource.postAndVerifyCreated(
                    host.getPorts(),
                    VendorMediaType.APPLICATION_HOST_INTERFACE_PORT_JSON,
                    mapping,
                    DtoHostInterfacePort.class);

            // List mapping and verify that there is one
            maps = dtoResource.getAndVerifyOk(
                    host.getPorts(),
                    VendorMediaType
                            .APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON,
                    DtoHostInterfacePort[].class);
            Assert.assertEquals(1, maps.length);

            // Remove mapping
            dtoResource.deleteAndVerifyNoContent(
                    mapping.getUri(),
                    VendorMediaType.APPLICATION_HOST_INTERFACE_PORT_JSON);

            // List mapping and verify that there is none
            maps = dtoResource.getAndVerifyOk(
                    host.getPorts(),
                    VendorMediaType
                            .APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON,
                    DtoHostInterfacePort[].class);
        }
    }

}
