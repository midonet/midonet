/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.host;

import com.midokura.midolman.host.state.HostDirectory;
import com.midokura.midolman.host.state.HostZkManager;
import com.midokura.midonet.api.VendorMediaType;
import com.midokura.midonet.api.host.rest_api.HostTopology;
import com.midokura.midonet.api.rest_api.DtoWebResource;
import com.midokura.midonet.api.rest_api.FuncTest;
import com.midokura.midonet.api.rest_api.Topology;
import com.midokura.midonet.api.zookeeper.StaticMockDirectory;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.client.MidonetMgmt;
import com.midokura.midonet.client.dto.DtoBridge;
import com.midokura.midonet.client.dto.DtoBridgePort;
import com.midokura.midonet.client.dto.DtoHost;
import com.midokura.midonet.client.dto.DtoHostInterfacePort;
import com.midokura.midonet.client.resource.*;
import com.midokura.midonet.client.resource.Host;
import com.midokura.midonet.client.resource.HostInterfacePort;

import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;
import junit.framework.Assert;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

import java.net.InetAddress;
import java.net.URI;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

@RunWith(Enclosed.class)
public class TestHostInterfacePort {

    public static final String ZK_ROOT_MIDOLMAN = "/test/midolman";

    public static class TestCrud extends JerseyTest {

        private DtoWebResource dtoResource;
        private Topology topology;
        private HostTopology hostTopology;
        private HostZkManager hostManager;
        private Directory rootDirectory;
        private MidonetMgmt mgmt;

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

            URI baseUri = resource().getURI();
            mgmt = new MidonetMgmt(baseUri.toString());
            mgmt.enableLogging();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Test
        public void testCrud() throws Exception {

            DtoHost host = hostTopology.getHost(host1Id);
            DtoBridgePort port1 = topology.getExtBridgePort("bridgePort1");

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

        @Test
        public void testClient() throws Exception {
            UUID hostId = UUID.randomUUID();

            HostDirectory.Metadata metadata = new HostDirectory.Metadata();
            metadata.setName("test");
            metadata.setAddresses(new InetAddress[]{
                InetAddress.getByAddress(
                    new byte[]{(byte) 193, (byte) 231, 30, (byte) 197})
            });

            hostManager.createHost(hostId, metadata);
            hostManager.makeAlive(hostId);

            ResourceCollection<Host> hosts = mgmt.getHosts();
            com.midokura.midonet.client.resource.Host host = hosts.get(0);

            // Create a bridge
            Bridge b1 = mgmt.addBridge()
                            .tenantId("tenant-1")
                            .name("bridge-1")
                            .create();

            BridgePort bp1 = b1.addExteriorPort().create();
            BridgePort bp2 = b1.addExteriorPort().create();


            HostInterfacePort hip1 = host.addHostInterfacePort()
                                                      .interfaceName("tap-1")
                                                      .portId(bp1.getId())
                                                      .create();

            HostInterfacePort hip2 = host.addHostInterfacePort()
                                                      .interfaceName("tap-2")
                                                      .portId(bp2.getId())
                                                      .create();

            ResourceCollection<HostInterfacePort> hips = host.getPorts();

            assertThat("There are two host interface port mappings.",
                       hips.size(), is(2));

            assertThat("Correct host id is returned", hip1.getHostId(),
                       is(host.getId()));
            assertThat("Correct host id is returned", hip2.getHostId(),
                       is(host.getId()));

            assertThat("Correct port id is returned",
                       hip1.getPortId(), is(bp1.getId()));
            assertThat("Correct port id is returned",
                       hip2.getPortId(), is(bp2.getId()));

            assertThat("Correct interface name is returned",
                       hip1.getInterfaceName(), is("tap-1"));
            assertThat("Correct interface name is returned",
                       hip2.getInterfaceName(), is("tap-2"));
        }
    }
}
