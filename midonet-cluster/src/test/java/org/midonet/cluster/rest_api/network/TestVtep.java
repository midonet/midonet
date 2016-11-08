/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.cluster.rest_api.network;

import java.net.InetAddress;
import java.net.URI;
import java.util.UUID;

import javax.ws.rs.core.Response.Status;

import scala.concurrent.Await;
import scala.concurrent.duration.Duration;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import org.midonet.client.dto.DtoBridge;
import org.midonet.client.dto.DtoBridgePort;
import org.midonet.client.dto.DtoError;
import org.midonet.client.dto.DtoPort;
import org.midonet.client.dto.DtoTunnelZone;
import org.midonet.client.dto.DtoVtep;
import org.midonet.client.dto.DtoVtepBinding;
import org.midonet.client.dto.DtoVtepPort;
import org.midonet.client.dto.DtoVxLanPort;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.models.Vtep;
import org.midonet.cluster.rest_api.models.VtepBinding;
import org.midonet.cluster.rest_api.models.VxLanPort;
import org.midonet.cluster.rest_api.rest_api.FuncTest;
import org.midonet.cluster.rest_api.rest_api.RestApiTestBase;
import org.midonet.cluster.rest_api.rest_api.TopologyBackdoor;
import org.midonet.cluster.rest_api.validation.MessageProperty;
import org.midonet.cluster.services.MidonetBackend;
import org.midonet.packets.IPv4Addr;

import static java.util.UUID.randomUUID;
import static java.util.concurrent.TimeUnit.SECONDS;
import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.CONFLICT;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.midonet.cluster.models.State.VtepConnectionState.VTEP_ERROR;
import static org.midonet.cluster.rest_api.network.TestPort.createBridgePort;
import static org.midonet.cluster.rest_api.validation.MessageProperty.IP_ADDR_INVALID;
import static org.midonet.cluster.rest_api.validation.MessageProperty.MAX_VALUE;
import static org.midonet.cluster.rest_api.validation.MessageProperty.MIN_VALUE;
import static org.midonet.cluster.rest_api.validation.MessageProperty.NON_NULL;
import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.TUNNEL_ZONE_ID_IS_INVALID;
import static org.midonet.cluster.rest_api.validation.MessageProperty.VTEP_HAS_BINDINGS;
import static org.midonet.cluster.rest_api.validation.MessageProperty.VTEP_PORT_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.VTEP_PORT_VLAN_PAIR_ALREADY_USED;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_BRIDGE_JSON_V4;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_PORT_V3_COLLECTION_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_PORT_V3_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_TUNNEL_ZONE_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_VTEP_BINDING_COLLECTION_JSON_V2;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_VTEP_BINDING_JSON_V2;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_VTEP_COLLECTION_JSON_V2;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_VTEP_JSON_V2;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_VTEP_PORT_COLLECTION_JSON;
import static org.midonet.cluster.util.SequenceDispenser.VxgwVni$;
import static org.midonet.southbound.vtep.mock.MockOvsdbVtep.physPortNames;

public class TestVtep extends RestApiTestBase {

    public TestVtep() {
        super(FuncTest.appDesc);
    }

    private UUID goodTunnelZone = null;
    private UUID badTunnelZone = null;

    public abstract static class MockableVtep {
        abstract public String mgmtIp();
        abstract public int mgmtPort();
        abstract public String[] portNames();
    }

    /** This VTEP is always mockable by default */
    public static final MockableVtep mockVtep1 = new MockableVtep() {
        public String mgmtIp() { return "250.132.36.225"; }
        public int mgmtPort() { return 12345; }
        public String[] portNames() {
            return new String[]{"eth0", "eth1", "eth_2", "Te 0/2"};
        }
    };

    public static final MockableVtep mockVtep2 = new MockableVtep() {
        public String mgmtIp() { return "30.20.3.99"; }
        public int mgmtPort() { return 3388; }
        public String[] portNames() {
            return new String[]{"eth0", "eth1", "eth_2", "Te 0/2"};
        }
    };

    @Before
    public void before() {
        URI tunnelZonesUri = app.getTunnelZones();
        DtoTunnelZone tz = new DtoTunnelZone();
        tz.setName("tz");
        tz.setType("vtep");
        tz = dtoResource.postAndVerifyCreated(tunnelZonesUri,
                  APPLICATION_TUNNEL_ZONE_JSON(), tz,
                  DtoTunnelZone.class);
        assertNotNull(tz.getId());
        goodTunnelZone = tz.getId();
        assertEquals("tz", tz.getName());
        while (badTunnelZone == null || badTunnelZone.equals(
            this.goodTunnelZone)) {
            badTunnelZone = randomUUID();
        }
    }

    @Test
    public void testCreate() {
        DtoVtep vtep = postVtep("10.0.0.1", 6632);
        assertEquals("10.0.0.1", vtep.getManagementIp());
        assertEquals(6632, vtep.getManagementPort());
    }

    @Test
    public void testCreateWithNullIPAddr() {
        DtoError error = postVtepWithError(null, 6621, BAD_REQUEST);
        assertErrorMatchesPropMsg(error, "managementIp", NON_NULL);
    }

    @Test
    public void testCreateWithBadTunnelZone() {
        DtoVtep vtep = new DtoVtep();
        vtep.setManagementIp("10.0.0.1");
        vtep.setManagementPort(6632);
        vtep.setTunnelZoneId(badTunnelZone);
        DtoError error = dtoResource.postAndVerifyError(app.getVteps(),
                                                        APPLICATION_VTEP_JSON_V2(),
                                                        vtep,
                                                        Status.BAD_REQUEST);
        assertErrorMatches(error, TUNNEL_ZONE_ID_IS_INVALID);
    }

    @Test
    public void testCreateWithNullTunnelZone() {
        DtoVtep vtep = new DtoVtep();
        vtep.setManagementIp(mockVtep1.mgmtIp());
        vtep.setManagementPort(mockVtep1.mgmtPort());
        vtep.setTunnelZoneId(null);
        DtoError error = dtoResource.postAndVerifyError(app.getVteps(),
                                                        APPLICATION_VTEP_JSON_V2(),
                                                        vtep,
                                                        Status.BAD_REQUEST);
        assertErrorMatches(error, TUNNEL_ZONE_ID_IS_INVALID);
    }

    @Test
    public void testCreateWithIllFormedIPAddr() {
        DtoError error = postVtepWithError("10.0.0.300", 6632, BAD_REQUEST);
        assertErrorMatchesPropMsg(error, "managementIp", IP_ADDR_INVALID);
    }

    @Test
    public void testCreateWithDuplicateIPAddr() {
        int port = 6632;
        String ipAddr = "10.0.0.2";
        postVtep(ipAddr, port);
        DtoError error = postVtepWithError(ipAddr, port + 1, CONFLICT);
        assertErrorMatches(error, MessageProperty.VTEP_EXISTS, ipAddr);
    }

    @Test
    @Ignore // we need to make the mock provider return non-connectable VTEPs
    public void testCreateWithInaccessibleIpAddr() {
        DtoVtep vtep = postVtep("10.0.0.1", 10001);
        assertEquals(VTEP_ERROR.toString(),
                     vtep.getConnectionState());
        assertNull(vtep.getDescription());
        assertEquals("10.0.0.1", vtep.getManagementIp());
        assertEquals(10001, vtep.getManagementPort());
        assertNull(vtep.getName());
        assertNull(vtep.getTunnelIpAddrs());
    }

    @Test
    @Ignore // fails because the host doesn't bring the list of addresses,
            // probably bc. we need to update the topology backdoor
    public void testCreateWithHostConflict() throws Exception {
        String ip = "10.255.255.1";
        InetAddress[] addrs = new InetAddress[]{InetAddress.getByName(ip)};
        FuncTest._injector
                .getInstance(TopologyBackdoor.class)
                .createHost(randomUUID(), "host-name", addrs);

        // Try add the VTEP with the same IP address.
        postVtepWithError(ip, 6632, Status.CONFLICT);
    }

    @Test
    public void testGet() {
        String ip = "10.0.0.1";
        DtoVtep v = postVtep(ip, 6632);
        DtoVtep vtep = getVtep(v.getId());
        assertEquals(ip, vtep.getManagementIp());
        assertEquals(6632, vtep.getManagementPort());
    }

    @Test
    public void testListVtepsWithNoVteps() {
        DtoVtep[] vteps = listVteps();
        assertEquals(0, vteps.length);
    }

    @Test
    public void testListVtepsWithTwoVteps() {
        // The mock client supports only one management IP/port, so
        // only one will successfully connect.
        DtoVtep[] expectedVteps = new DtoVtep[2];
        expectedVteps[0] = postVtep("10.0.0.1", 10001);
        assertEquals(expectedVteps[0].getManagementIp(), "10.0.0.1");
        assertEquals(expectedVteps[0].getManagementPort(), 10001);
        expectedVteps[1] = postVtep("10.0.0.2", 10002);
        assertEquals(expectedVteps[1].getManagementIp(), "10.0.0.2");
        assertEquals(expectedVteps[1].getManagementPort(), 10002);

        DtoVtep[] actualVteps = listVteps();
        assertEquals(2, actualVteps.length);
        assertThat(actualVteps, arrayContainingInAnyOrder(expectedVteps));
    }

    @Test
    public void testDeleteVtep() {
        DtoVtep vtep = postVtep();
        DtoBridge bridge = postBridge("bridge1");
        DtoVtepBinding binding =
                addAndVerifyBinding(vtep, bridge.getId(),
                                    physPortNames().get(0), 1);

        // Cannot delete the VTEP because it has bindings.
        DtoError error = dtoResource.deleteAndVerifyBadRequest(
                vtep.getUri(), APPLICATION_VTEP_JSON_V2());
        assertErrorMatches(error, VTEP_HAS_BINDINGS, vtep.getManagementIp());

        // Delete the binding.
        deleteBinding(binding.getUri());

        // Can delete the VTEP now.
        dtoResource.deleteAndVerifyNoContent(vtep.getUri(),
                                             APPLICATION_VTEP_JSON_V2());
    }

    @Test
    public void testDeleteNonexistingVtep() {
        Vtep vtep = new Vtep();
        vtep.id = randomUUID();
        vtep.managementIp = "1.2.3.4";
        vtep.setBaseUri(app.getUri());
        DtoError error = dtoResource.deleteAndVerifyNotFound(vtep.getUri(),
            APPLICATION_VTEP_JSON_V2());
        assertErrorMatches(error, RESOURCE_NOT_FOUND, "Vtep", vtep.id);
    }

    @Test
    public void testAddBindingsSingleNetworkMultipleVteps() {
        DtoVtep vtep1 = postVtep("10.0.0.1", 6632);
        DtoVtep vtep2 = postVtep("10.0.0.2", 6633);

        assertEquals(0, listBindings(vtep1).length);
        assertEquals(0, listBindings(vtep2).length);

        DtoBridge br = postBridge("network1");
        DtoVtepBinding b1 = addAndVerifyBinding(vtep1, br.getId(),
                                                physPortNames().get(0), 1);
        DtoVtepBinding b2 = addAndVerifyBinding(vtep1, br.getId(),
                                                physPortNames().get(1), 1,
                                                false);
        addAndVerifyBinding(vtep2, br.getId(), physPortNames().get(0), 2);
        addAndVerifyBinding(vtep2, br.getId(), physPortNames().get(1), 2,
                            false);

        br = getBridge(br.getId());
        assertEquals("A vxlan port per vtep", 2, br.getVxLanPortIds().size());
        assertEquals(2, listBindings(vtep1).length);
        assertEquals(2, listBindings(vtep2).length);

        DtoVxLanPort vxPort1 = getVxLanPort(br.getVxLanPortIds().get(0));
        DtoVxLanPort vxPort2 = getVxLanPort(br.getVxLanPortIds().get(1));

        assertEquals(vtep1.getId(), vxPort1.getVtepId());
        assertEquals(vtep2.getId(), vxPort2.getVtepId());

        // Deleting a binding on a vtep has no other secondary effects
        deleteBinding(b1.getUri());

        br = getBridge(br.getId());
        assertEquals("A vxlan port per vtep", 2, br.getVxLanPortIds().size());
        assertEquals(1, listBindings(vtep1).length);
        assertEquals(2, listBindings(vtep2).length);

        vxPort1 = getVxLanPort(br.getVxLanPortIds().get(0));
        vxPort2 = getVxLanPort(br.getVxLanPortIds().get(1));

        assertEquals(vtep1.getId(), vxPort1.getVtepId());
        assertEquals(vtep2.getId(), vxPort2.getVtepId());

        // Deleting the last binding on a vtep kills the associated vxlan port
        deleteBinding(b2.getUri());

        br = getBridge(br.getId());
        assertEquals(1, br.getVxLanPortIds().size());
        assertEquals(0, listBindings(vtep1).length);
        assertEquals(2, listBindings(vtep2).length);

        // Only vxlan port 2 remains
        vxPort2 = getVxLanPort(br.getVxLanPortIds().get(0));
        assertEquals(vtep2.getId(), vxPort2.getVtepId());
    }

    // Tests both add and remove, so no need to bother having a separate one for
    // just adding
    @Test
    public void testAddRemoveBindings() {
        DtoVtep vtep = postVtep();

        assertEquals(0, listBindings(vtep).length);

        DtoBridge br1 = postBridge("network1");
        DtoBridge br2 = postBridge("network2");
        DtoVtepBinding bdg1 = addAndVerifyBinding(vtep, br1.getId(),
                                                  physPortNames().get(0), 1);
        DtoVtepBinding bdg2 = addAndVerifyBinding(vtep, br2.getId(),
                                                  physPortNames().get(0), 2);

        assertEquals(br1.getId(), bdg1.getNetworkId());
        assertEquals(br2.getId(), bdg2.getNetworkId());

        br1 = getBridge(br1.getId());
        br2 = getBridge(br2.getId());

        DtoVxLanPort vxlanPort1 = getVxLanPort(br1.getVxLanPortIds().get(0));
        DtoVxLanPort vxlanPort2 = getVxLanPort(br2.getVxLanPortIds().get(0));

        DtoVtepBinding[] bindings = listBindings(vtep);
        assertEquals(2, bindings.length);
        assertThat(bindings, arrayContainingInAnyOrder(bdg1, bdg2));

        deleteBinding(bdg1.getUri());

        bindings = listBindings(vtep);
        assertEquals(1, bindings.length);
        assertThat(bindings, arrayContainingInAnyOrder(bdg2));
        dtoResource.getAndVerifyNotFound(vxlanPort1.getUri(),
                                         APPLICATION_PORT_V3_JSON());
        dtoResource.getAndVerifyOk(vxlanPort2.getUri(),
                                   APPLICATION_PORT_V3_JSON(),
                                   DtoVxLanPort.class);
        br1 = getBridge(br1.getId());
        assertTrue(br1.getVxLanPortIds().isEmpty());

        br2 = getBridge(br2.getId());
        assertEquals(vxlanPort2.getId(), br2.getVxLanPortIds().get(0));

        deleteBinding(bdg2.getUri());
        dtoResource.getAndVerifyNotFound(vxlanPort2.getUri(),
                                         APPLICATION_PORT_V3_JSON());

        br2 = getBridge(br2.getId());
        assertTrue(br2.getVxLanPortIds().isEmpty());

        bindings = listBindings(vtep);
        assertEquals(0, bindings.length);
    }

    @Test
    public void testAddRemoveMultipleBindingsOnSingleNetwork() {
        testAddRemoveBindings(physPortNames().get(0));
    }

    @Test
    public void testNetworkCantDeleteWhenBindings() {
        DtoVtep vtep = postVtep();
        DtoBridge br = postBridge("network1");
        UUID id = br.getId();
        DtoVtepBinding binding1 = addAndVerifyBinding(vtep, id,
                                                      physPortNames().get(0), 1);
        dtoResource.deleteAndVerifyError(br.getUri(),
                                         APPLICATION_BRIDGE_JSON_V4(),
                                         CONFLICT.getStatusCode());

        dtoResource.deleteAndVerifyNoContent(binding1.getUri(),
                                             APPLICATION_VTEP_BINDING_JSON_V2());
    }

    @Test
    public void testAddRemoveBindingWithWeirdName() {
        testAddRemoveBindings(physPortNames().get(3));
    }

    @Test
    public void testDeleteBindingIdempotency() {
        DtoVtep vtep = postVtep();
        DtoBridge br = postBridge("n");
        DtoVtepBinding binding = addAndVerifyBinding(
            vtep, br.getId(), physPortNames().get(1), 1);
        deleteBinding(binding.getUri());
        dtoResource.deleteAndVerifyNoContent(binding.getUri(),
                                             APPLICATION_VTEP_BINDING_JSON_V2());
    }

    @Test
    public void testAddBindingWithNegativeVlanId() {
        DtoVtep vtep = postVtep();
        DtoError error = postBindingWithError(
            vtep,
            makeBinding(vtep.getId(), physPortNames().get(0), -1, randomUUID()),
            BAD_REQUEST);
        assertErrorMatchesPropMsg(error, "vlanId", MIN_VALUE, 0);
    }

    @Test
    public void testAddBindingWith4096VlanId() {
        DtoVtep vtep = postVtep();
        DtoError error = postBindingWithError(
            vtep,
            makeBinding(vtep.getId(), physPortNames().get(0),
                        4096, randomUUID()),
            BAD_REQUEST);
        assertErrorMatchesPropMsg(error, "vlanId", MAX_VALUE, 4095);
    }

    @Test
    public void testAddBindingWithNullNetworkId() {
        DtoVtep vtep = postVtep();
        DtoError error = postBindingWithError(
            vtep,
            makeBinding(vtep.getId(), physPortNames().get(0), 1, null),
            BAD_REQUEST);
        assertErrorMatchesPropMsg(error, "networkId", NON_NULL);
    }

    @Test
    public void testAddBindingWithUnrecognizedNetworkId() {
        DtoVtep vtep = postVtep();
        UUID networkId = randomUUID();
        DtoError error = postBindingWithError(vtep,
                makeBinding(vtep.getId(), "eth0", 1, networkId), BAD_REQUEST);
        assertErrorMatches(error, RESOURCE_NOT_FOUND, "Network", networkId);
    }

    @Test
    public void testAddBindingWithNullPortName() {
        DtoVtep vtep = postVtep();
        DtoError error = postBindingWithError(vtep,
              makeBinding(vtep.getId(), null, 1, randomUUID()), BAD_REQUEST);
        assertErrorMatchesPropMsg(error, "portName", NON_NULL);
    }

    @Test
    public void testAddBindingWithUnrecognizedPortName() {
        DtoBridge bridge = postBridge("network1");
        DtoVtep vtep = postVtep();
        DtoVtepBinding binding = makeBinding(vtep.getId(), "blah", 1,
                                             bridge.getId());
        DtoError err = postBindingWithError(vtep, binding, NOT_FOUND);
        assertErrorMatches(err, VTEP_PORT_NOT_FOUND, vtep.getManagementIp(),
                           vtep.getManagementPort(), "blah");
    }

    /* The same port/vlan pair on the same vtep should not be allowed.
     */
    @Test
    public void testAddConflictingBinding() {
        DtoBridge bridge1 = postBridge("network1");
        DtoBridge bridge2 = postBridge("network2");
        DtoVtep vtep = postVtep();
        DtoVtepBinding b1 = addAndVerifyBinding(vtep, bridge1.getId(),
                                                physPortNames().get(0), 3);

        DtoError err = postBindingWithError(vtep,
                                            makeBinding(vtep.getId(),
                                                        physPortNames().get(0),
                                                        3, bridge2.getId()),
                                            CONFLICT);
        assertErrorMatches(err, VTEP_PORT_VLAN_PAIR_ALREADY_USED,
                           vtep.getManagementIp(), vtep.getManagementPort(),
                           physPortNames().get(0), 3, bridge1.getId());

        // Ensure that the second bridge didn't keep a vxlan port
        bridge2 = getBridge(bridge2.getId());
        assertTrue((bridge2.getVxLanPortIds().isEmpty()));

        // Ensure that creation of an existing binding is not possible
        err = postBindingWithError(vtep,
                                   makeBinding(vtep.getId(),
                                               physPortNames().get(0), 3,
                                               bridge1.getId()),
                                   CONFLICT);
        assertErrorMatches(err, VTEP_PORT_VLAN_PAIR_ALREADY_USED,
                           vtep.getManagementIp(), vtep.getManagementPort(),
                           physPortNames().get(0), 3, bridge1.getId());
    }

    @Test
    public void testListBindings() {
        // First try getting the bindings when the VTEP is first
        // created. The mock VTEP client is preseeded with a non-
        // Midonet binding, so there should actually be one, but the
        // Midonet API should ignore it and return an empty list.
        DtoVtep vtep = postVtep();
        DtoVtepBinding[] actualBindings = listBindings(vtep);
        assertEquals(0, actualBindings.length);

        DtoBridge bridge = postBridge("network1");
        DtoBridge bridge2 = postBridge("network2");
        DtoVtepBinding[] expectedBindings = new DtoVtepBinding[2];
        expectedBindings[0] = postBinding(vtep,
                                          makeBinding(vtep.getId(),
                                                      physPortNames().get(0), 1,
                                                      bridge.getId()));
        expectedBindings[1] = postBinding(vtep,
                                          makeBinding(vtep.getId(),
                                                      physPortNames().get(1), 5,
                                                      bridge2.getId()));

        actualBindings = listBindings(vtep);
        assertThat(actualBindings, arrayContainingInAnyOrder(expectedBindings));
    }

    @Test
    public void testListBindingsWithUnrecognizedVtep() throws Exception {
        Vtep v = new Vtep();
        v.id = randomUUID();
        v.setBaseUri(app.getUri());
        v.managementIp = "10.10.10.10";
        DtoError error = dtoResource.getAndVerifyNotFound(v.getBindings(),
                APPLICATION_VTEP_BINDING_COLLECTION_JSON_V2());
        assertErrorMatches(error, RESOURCE_NOT_FOUND, "Vtep", v.id);
    }

    @Test
    public void testGetBindingWithUnrecognizedVtep() {
        VtepBinding vb = new VtepBinding();
        vb.vtepId = randomUUID();
        vb.portName = "a_port";
        vb.vlanId = (short)1;
        vb.setBaseUri(app.getUri());
        URI bindingUri = vb.getUri();
        DtoError error = dtoResource.getAndVerifyNotFound(bindingUri,
                                              APPLICATION_VTEP_BINDING_JSON_V2());
        assertErrorMatches(error, RESOURCE_NOT_FOUND, "Vtep", vb.vtepId);
    }

    @Test
    @Ignore("TODO FIXME - pending implementation in v2")
    // This requires manipulating state keys, as the ports are now written to
    // ZK by the vxgw service as state keys
    public void testListVtepPorts() {
        DtoVtep vtep = postVtep();
        DtoVtepPort[] ports = dtoResource.getAndVerifyOk(vtep.getPorts(),
                 APPLICATION_VTEP_PORT_COLLECTION_JSON(), DtoVtepPort[].class);

        DtoVtepPort[] expectedPorts =
            new DtoVtepPort[mockVtep1.portNames().length];
        for (int i = 0; i < mockVtep1.portNames().length; i++)
            expectedPorts[i] = new DtoVtepPort(mockVtep1.portNames()[i],
                                               mockVtep1.portNames()[i]
                                               + "-desc");

        assertThat(ports, arrayContainingInAnyOrder(expectedPorts));
    }

    @Test
    public void testDeleteVxLanPortDisallowed() {
        DtoBridge bridge1 = postBridge("bridge1");
        DtoBridge bridge2 = postBridge("bridge2");
        DtoVtep vtep = postVtep();

        DtoVtepBinding br1bi1 = postBinding(vtep,
                        makeBinding(vtep.getId(), physPortNames().get(0), 1,
                                    bridge1.getId()));
        DtoVtepBinding br1bi2 = postBinding(vtep,
                        makeBinding(vtep.getId(), physPortNames().get(1), 2,
                                    bridge1.getId()));
        DtoVtepBinding br2bi1 = postBinding(vtep,
                        makeBinding(vtep.getId(), physPortNames().get(0), 3,
                                    bridge2.getId()));
        DtoVtepBinding br2bi2 = postBinding(vtep,
                        makeBinding(vtep.getId(), physPortNames().get(1), 4,
                                    bridge2.getId()));

        DtoVtepBinding[] bindings = listBindings(vtep);
        assertThat(bindings, arrayContainingInAnyOrder(br1bi1, br1bi2,
                                                       br2bi1, br2bi2));

        bridge1 = getBridge(bridge1.getId());
        VxLanPort p = new VxLanPort();
        p.setBaseUri(app.getUri());
        p.id = bridge1.getVxLanPortIds().get(0);
        dtoResource.deleteAndVerifyError(p.getUri(),
                                         APPLICATION_PORT_V3_JSON(),
                                         CONFLICT.getStatusCode());
        bindings = listBindings(vtep);
        assertThat(bindings, arrayContainingInAnyOrder(br1bi1, br1bi2,
                                                       br2bi1, br2bi2));
    }

    @Test
    @Ignore // pending in v2
    public void testListBridgePortsExcludesVxlanPort() {
        DtoBridge bridge = postBridge("bridge1");
        DtoBridgePort bridgePort = postBridgePort(
            createBridgePort(null, bridge.getId(), null, null, null),
            bridge);

        DtoVtep vtep = postVtep();
        postBinding(vtep, makeBinding(vtep.getId(), physPortNames().get(0),
                                      0, bridge.getId()));

        DtoBridgePort[] bridgePorts = dtoResource.getAndVerifyOk(
            bridge.getPorts(), APPLICATION_PORT_V3_COLLECTION_JSON(),
            DtoBridgePort[].class);
        assertThat(bridgePorts, arrayContainingInAnyOrder(bridgePort));
    }

    @Test
    public void testDeleteAndRecreateBindings() {
        DtoBridge bridge = postBridge("bridge1");
        DtoVtep vtep = postVtep();
        DtoVtepBinding binding = postBinding(vtep,
            makeBinding(vtep.getId(), physPortNames().get(1), 0,
                        bridge.getId()));
        deleteBinding(binding.getUri());
        postBinding(vtep,
                    makeBinding(vtep.getId(), physPortNames().get(2), 0,
                                bridge.getId()));
    }

    private DtoVtep makeVtep(String mgmtIpAddr, int mgmtPort,
                             UUID tunnelZoneId) {
        DtoVtep vtep = new DtoVtep();
        vtep.setManagementIp(mgmtIpAddr);
        vtep.setManagementPort(mgmtPort);
        vtep.setTunnelZoneId(tunnelZoneId);
        return vtep;
    }

    private DtoVtep postVtep() {
        return postVtep(IPv4Addr.random().toString(), 6632);
    }

    private DtoVtep postVtep(String mgmtIpAddr, int mgmtPort) {
        return postVtep(makeVtep(mgmtIpAddr, mgmtPort, goodTunnelZone));
    }

    private DtoVtep postVtep(DtoVtep vtep) {
        return dtoResource.postAndVerifyCreated(
            app.getVteps(), APPLICATION_VTEP_JSON_V2(), vtep, DtoVtep.class);
    }

    private DtoError postVtepWithError(
            String mgmtIpAddr, int mgmtPort, Status status) {
        DtoVtep vtep = makeVtep(mgmtIpAddr, mgmtPort, goodTunnelZone);
        return dtoResource.postAndVerifyError(app.getVteps(),
                                              APPLICATION_VTEP_JSON_V2(),
                                              vtep, status);
    }

    private DtoVtep getVtep(UUID id) {
        return dtoResource.getAndVerifyOk(
            app.getVtep(id), APPLICATION_VTEP_JSON_V2(), DtoVtep.class);
    }

    private DtoVtep[] listVteps() {
        return dtoResource.getAndVerifyOk(app.getVteps(),
                                          APPLICATION_VTEP_COLLECTION_JSON_V2(),
                                          DtoVtep[].class);
    }

    private DtoVtepBinding makeBinding(UUID vtepId, String portName, int vlanId,
                                       UUID networkId) {
        DtoVtepBinding binding = new DtoVtepBinding();
        binding.setVtepId(vtepId);
        binding.setPortName(portName);
        binding.setVlanId((short)vlanId);
        binding.setNetworkId(networkId);
        return binding;
    }

    private DtoVtepBinding postBinding(DtoVtep vtep, DtoVtepBinding binding) {
        return dtoResource.postAndVerifyCreated(
            vtep.getBindings(), APPLICATION_VTEP_BINDING_JSON_V2(),
            binding, DtoVtepBinding.class);
    }

    private DtoError postBindingWithError(
            DtoVtep vtep, DtoVtepBinding binding, Status status) {
        return dtoResource.postAndVerifyError(vtep.getBindings(),
                                              APPLICATION_VTEP_BINDING_JSON_V2(),
                                              binding, status);
    }

    private DtoVtepBinding[] listBindings(DtoVtep vtep) {
        return dtoResource.getAndVerifyOk(vtep.getBindings(),
                APPLICATION_VTEP_BINDING_COLLECTION_JSON_V2(),
                DtoVtepBinding[].class);
    }

    private void deleteBinding(URI uri) {
        dtoResource.deleteAndVerifyNoContent(uri,
                                             APPLICATION_VTEP_BINDING_JSON_V2());
    }

    private DtoVxLanPort getVxLanPort(UUID id) {
        DtoPort port = getPort(id);
        assertNotNull(port);
        return (DtoVxLanPort)port;
    }

    private void testAddRemoveBindings(String portName) {

        DtoVtep vtep = postVtep();

        assertEquals(0, listBindings(vtep).length);

        DtoBridge br = postBridge("network1");
        UUID id = br.getId();
        DtoVtepBinding binding1 = addAndVerifyBinding(vtep, id, portName, 1);
        DtoVtepBinding binding2 = addAndVerifyBinding(vtep, id, portName, 2,
                                                      false);

        br = getBridge(binding1.getNetworkId());
        assertEquals(br.getId(), binding1.getNetworkId());

        DtoVxLanPort vxlanPort = getVxLanPort(br.getVxLanPortIds().get(0));

        DtoVtepBinding[] bindings = listBindings(vtep);
        assertThat(bindings, arrayContainingInAnyOrder(binding1, binding2));

        deleteBinding(binding1.getUri());

        bindings = listBindings(vtep);
        assertThat(bindings, arrayContainingInAnyOrder(binding2));
        dtoResource.getAndVerifyOk(vxlanPort.getUri(),
                                   APPLICATION_PORT_V3_JSON(),
                                   DtoVxLanPort.class);

        vxlanPort = getVxLanPort(vxlanPort.getId()); // should exist

        deleteBinding(binding2.getUri());
        dtoResource.getAndVerifyNotFound(vxlanPort.getUri(),
                                         APPLICATION_PORT_V3_JSON());

        bindings = listBindings(vtep);
        assertEquals(0, bindings.length);
    }

    // Use when the binding is the first one from the given bridge id to the
    // given vtep.
    private DtoVtepBinding addAndVerifyBinding(DtoVtep vtep,
                                               UUID bridgeId,
                                               String portName, int vlan) {
        return addAndVerifyBinding(vtep, bridgeId, portName, vlan, true);
    }

    // If the firstBinding param is set to true, we'll understand that this
    // is the first binding of the bridge to the vtep, and thus the API is
    // expected to create a new VxLAN port on the bridge.  Otherwise, it'll
    // use an already created one.
    private DtoVtepBinding addAndVerifyBinding(DtoVtep vtep,
                                               UUID bridgeId,
                                               String portName, int vlan,
                                               boolean firstBinding) {
        DtoBridge oldBridge = getBridge(bridgeId);
        DtoVtepBinding binding = postBinding(vtep,
                                             makeBinding(vtep.getId(),
                                                         portName, vlan,
                                                         bridgeId));
        assertEquals(bridgeId, binding.getNetworkId());
        assertEquals(portName, binding.getPortName());
        assertEquals(vlan, binding.getVlanId());

        // Should create a VXLAN port on the specified bridge.
        DtoBridge newBridge = getBridge(bridgeId);

        MidonetBackend b = FuncTest._injector.getInstance(MidonetBackend.class);

        int vni = 0;
        try {
            vni = Await.result(b.store().get(Topology.Network.class, bridgeId),
                               Duration.create(1, SECONDS)).getVni();
        } catch (Exception e) {
            fail("Exception retrieving bridge: " + bridgeId);
        }

        assertTrue(vni >= VxgwVni$.MODULE$.seed());

        if (firstBinding) {
            assertEquals(newBridge.getVxLanPortIds().size(),
                         oldBridge.getVxLanPortIds().size() + 1);
        } else {
            assertEquals(newBridge.getVxLanPortIds().size(),
                         oldBridge.getVxLanPortIds().size());
        }

        // And we should find the vx port pointing to the VTEP
        DtoVxLanPort port = null;
        for (UUID id : newBridge.getVxLanPortIds()) {
            DtoVxLanPort candidate = getVxLanPort(id);
            if (candidate.getVtepId().equals(vtep.getId())) {
                port = candidate;
                break;
            }
        }
        assertNotNull(port);
        assertEquals(vtep.getId(), port.getVtepId());
        assertEquals(newBridge.getId(), port.getDeviceId());

        return binding;
    }

}
