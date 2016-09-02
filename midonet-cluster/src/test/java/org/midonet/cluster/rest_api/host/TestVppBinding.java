/*
 * Copyright 2016 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.cluster.rest_api.host;

import java.net.URI;
import java.util.UUID;

import javax.ws.rs.core.UriBuilder;

import com.sun.jersey.api.client.ClientResponse;

import org.junit.Assert;
import org.junit.Test;

import org.midonet.client.dto.DtoBridge;
import org.midonet.client.dto.DtoBridgePort;
import org.midonet.client.dto.DtoError;
import org.midonet.client.dto.DtoHost;
import org.midonet.client.dto.DtoHostInterfacePort;
import org.midonet.client.dto.DtoRouter;
import org.midonet.client.dto.DtoTunnelZone;
import org.midonet.client.dto.DtoTunnelZoneHost;
import org.midonet.client.dto.DtoVppBinding;
import org.midonet.cluster.rest_api.host.rest_api.HostTopology;
import org.midonet.cluster.rest_api.models.VppBinding;
import org.midonet.cluster.rest_api.rest_api.DtoWebResource;
import org.midonet.cluster.rest_api.rest_api.FuncTest;
import org.midonet.cluster.rest_api.rest_api.RestApiTestBase;
import org.midonet.cluster.rest_api.rest_api.Topology;

import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.CONFLICT;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.HOST_IS_NOT_IN_ANY_TUNNEL_ZONE;
import static org.midonet.cluster.rest_api.validation.MessageProperty.PORT_ALREADY_BOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_HOST_INTERFACE_PORT_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_HOST_VPP_BINDING_COLLECTION_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_HOST_VPP_BINDING_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_TUNNEL_ZONE_HOST_JSON;

public class TestVppBinding extends RestApiTestBase {

    private DtoWebResource dtoResource;
    private Topology topology;
    private HostTopology hostTopology;

    private final UUID host1Id = UUID.randomUUID();

    public TestVppBinding() {
        super(FuncTest.appDesc);
    }

    @Override
    public void setUp() throws Exception {
        dtoResource = new DtoWebResource(resource());

        DtoHost host1 = new DtoHost();
        host1.setName("host1");

        DtoTunnelZone tunnelZone1 = new DtoTunnelZone();
        tunnelZone1.setName("tunnelZone1");

        DtoBridge bridge1 = new DtoBridge();
        bridge1.setName("bridge1");
        bridge1.setTenantId("tenant1");

        DtoRouter router1 = new DtoRouter();
        router1.setName("router1");
        router1.setTenantId("tenant1");

        DtoBridgePort bridgePort1 = new DtoBridgePort();
        DtoBridgePort bridgePort2 = new DtoBridgePort();

        topology = new Topology.Builder(dtoResource)
            .create("router1", router1)
            .create("bridge1", bridge1)
            .create("bridge1", "bridgePort1", bridgePort1)
            .create("bridge1", "bridgePort2", bridgePort2)
            .build();

        hostTopology = new HostTopology.Builder(dtoResource)
            .create(host1Id, host1)
            .create("tunnelZone1", tunnelZone1)
            .build();
    }

    @Test
    public void testCrud() {
        DtoHost host1 = hostTopology.getHost(host1Id);
        DtoBridgePort port1 = topology.getBridgePort("bridgePort1");

        // Bind the host to the tunnel zone.
        DtoTunnelZone tz = hostTopology.getGreTunnelZone("tunnelZone1");
        Assert.assertNotNull(tz);

        // Map a tunnel zone to a host.
        DtoTunnelZoneHost mapping = new DtoTunnelZoneHost();
        mapping.setHostId(host1Id);
        mapping.setIpAddress("10.0.0.1");
        dtoResource.postAndVerifyCreated(
            tz.getHosts(),
            APPLICATION_TUNNEL_ZONE_HOST_JSON(),
            mapping,
            DtoTunnelZoneHost.class);

        // List the VPP bindings.
        DtoVppBinding[] bindings = dtoResource.getAndVerifyOk(
            host1.getVppBindings(),
            APPLICATION_HOST_VPP_BINDING_COLLECTION_JSON(),
            DtoVppBinding[].class);
        Assert.assertEquals(0, bindings.length);

        // Create a VPP binding.
        DtoVppBinding binding1 = new DtoVppBinding();
        binding1.hostId = host1Id;
        binding1.portId = port1.getId();
        binding1.interfaceName = "eth0";
        binding1 = dtoResource.postAndVerifyCreated(
            host1.getVppBindings(),
            APPLICATION_HOST_VPP_BINDING_JSON(),
            binding1,
            DtoVppBinding.class);
        Assert.assertNotNull(binding1);

        // List the VPP bindings.
        bindings = dtoResource.getAndVerifyOk(
            host1.getVppBindings(),
            APPLICATION_HOST_VPP_BINDING_COLLECTION_JSON(),
            DtoVppBinding[].class);
        Assert.assertEquals(1, bindings.length);

        // Get the VPP binding.
        DtoVppBinding binding2 = dtoResource.getAndVerifyOk(
            bindings[0].uri,
            APPLICATION_HOST_VPP_BINDING_JSON(),
            DtoVppBinding.class);
        Assert.assertNotNull(binding2);
        Assert.assertEquals(binding1, binding2);

        // Delete the VPP binding.
        dtoResource.deleteAndVerifyNoContent(
            binding2.uri,
            APPLICATION_HOST_VPP_BINDING_JSON());

        // List the VPP bindings.
        bindings = dtoResource.getAndVerifyOk(
            host1.getVppBindings(),
            APPLICATION_HOST_VPP_BINDING_COLLECTION_JSON(),
            DtoVppBinding[].class);
        Assert.assertEquals(0, bindings.length);
    }

    @Test
    public void testCreateWhenHostIsNotInAnyTunnelZone() {
        DtoHost host1 = hostTopology.getHost(host1Id);
        DtoBridgePort port1 = topology.getBridgePort("bridgePort1");

        // Bind the host to the tunnel zone.
        DtoTunnelZone tz = hostTopology.getGreTunnelZone("tunnelZone1");
        Assert.assertNotNull(tz);

        // Create a VPP binding.
        DtoVppBinding binding1 = new DtoVppBinding();
        binding1.hostId = host1Id;
        binding1.portId = port1.getId();
        binding1.interfaceName = "eth0";
        ClientResponse response = dtoResource.postAndVerifyStatus(
            host1.getVppBindings(),
            APPLICATION_HOST_VPP_BINDING_JSON(),
            binding1,
            CONFLICT.getStatusCode());
        DtoError error = response.getEntity(DtoError.class);
        assertErrorMatchesLiteral(
            error, getMessage(HOST_IS_NOT_IN_ANY_TUNNEL_ZONE, host1Id));
    }

    @Test
    public void testCreateWhenPortAlreadyBoundToInterface() {
        DtoHost host1 = hostTopology.getHost(host1Id);
        DtoBridgePort port1 = topology.getBridgePort("bridgePort1");

        // Bind the host to the tunnel zone.
        DtoTunnelZone tz = hostTopology.getGreTunnelZone("tunnelZone1");
        Assert.assertNotNull(tz);

        // Map a tunnel zone to a host.
        DtoTunnelZoneHost mapping = new DtoTunnelZoneHost();
        mapping.setHostId(host1Id);
        mapping.setIpAddress("10.0.0.1");
        dtoResource.postAndVerifyCreated(
            tz.getHosts(),
            APPLICATION_TUNNEL_ZONE_HOST_JSON(),
            mapping,
            DtoTunnelZoneHost.class);

        // Create a datapath port binding.
        DtoHostInterfacePort binding0 = new DtoHostInterfacePort();
        binding0.setPortId(port1.getId());
        binding0.setInterfaceName("eth0");
        dtoResource.postAndVerifyCreated(
            host1.getPorts(),
            APPLICATION_HOST_INTERFACE_PORT_JSON(),
            binding0,
            DtoHostInterfacePort.class);

        // Create a VPP binding.
        DtoVppBinding binding1 = new DtoVppBinding();
        binding1.hostId = host1Id;
        binding1.portId = port1.getId();
        binding1.interfaceName = "eth1";
        ClientResponse response = dtoResource.postAndVerifyStatus(
            host1.getVppBindings(),
            APPLICATION_HOST_VPP_BINDING_JSON(),
            binding1,
            CONFLICT.getStatusCode());
        DtoError error = response.getEntity(DtoError.class);
        assertErrorMatchesLiteral(
            error, getMessage(PORT_ALREADY_BOUND, port1.getId()));
    }

    @Test
    public void testCreateWhenPortAlreadyBoundToVpp() {
        DtoHost host1 = hostTopology.getHost(host1Id);
        DtoBridgePort port1 = topology.getBridgePort("bridgePort1");

        // Bind the host to the tunnel zone.
        DtoTunnelZone tz = hostTopology.getGreTunnelZone("tunnelZone1");
        Assert.assertNotNull(tz);

        // Map a tunnel zone to a host.
        DtoTunnelZoneHost mapping = new DtoTunnelZoneHost();
        mapping.setHostId(host1Id);
        mapping.setIpAddress("10.0.0.1");
        dtoResource.postAndVerifyCreated(
            tz.getHosts(),
            APPLICATION_TUNNEL_ZONE_HOST_JSON(),
            mapping,
            DtoTunnelZoneHost.class);

        // Create a VPP binding.
        DtoVppBinding binding1 = new DtoVppBinding();
        binding1.hostId = host1Id;
        binding1.portId = port1.getId();
        binding1.interfaceName = "eth0";
        binding1 = dtoResource.postAndVerifyCreated(
            host1.getVppBindings(),
            APPLICATION_HOST_VPP_BINDING_JSON(),
            binding1,
            DtoVppBinding.class);
        Assert.assertNotNull(binding1);

        // Create a VPP binding.
        binding1.hostId = host1Id;
        binding1.portId = port1.getId();
        binding1.interfaceName = "eth1";
        ClientResponse response = dtoResource.postAndVerifyStatus(
            host1.getVppBindings(),
            APPLICATION_HOST_VPP_BINDING_JSON(),
            binding1,
            CONFLICT.getStatusCode());
        DtoError error = response.getEntity(DtoError.class);
        assertErrorMatchesLiteral(
            error, getMessage(PORT_ALREADY_BOUND, port1.getId()));
    }

    @Test
    public void testDeleteNonExistingPort() {
        DtoHost host1 = hostTopology.getHost(host1Id);

        // Delete the VPP binding.
        URI uri = UriBuilder.fromUri(host1.getVppBindings())
            .segment(UUID.randomUUID().toString()).build();
        dtoResource.deleteAndVerifyStatus(
            uri,
            APPLICATION_HOST_VPP_BINDING_JSON(),
            NOT_FOUND.getStatusCode());
    }

    @Test
    public void testDeleteNonExistingBinding() {
        DtoHost host1 = hostTopology.getHost(host1Id);
        DtoBridgePort port1 = topology.getBridgePort("bridgePort1");

        // Delete the VPP binding.
        URI uri = UriBuilder.fromUri(host1.getVppBindings())
                            .segment(port1.getId().toString()).build();
        dtoResource.deleteAndVerifyStatus(
            uri,
            APPLICATION_HOST_VPP_BINDING_JSON(),
            BAD_REQUEST.getStatusCode());
    }

    @Test
    public void testToString() {
        VppBinding binding = new VppBinding();
        Assert.assertNotNull(binding.toString());
    }
}
