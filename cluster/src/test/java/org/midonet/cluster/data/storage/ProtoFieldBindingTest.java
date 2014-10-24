/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.cluster.data.storage;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.google.common.collect.ListMultimap;
import org.junit.Test;

import org.midonet.cluster.data.storage.FieldBinding.DeleteAction;
import org.midonet.cluster.models.Commons;
import org.midonet.cluster.models.Devices;
import org.midonet.cluster.models.TestModels;

import static org.hamcrest.Matchers.contains;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.midonet.cluster.models.Devices.Chain;
import static org.midonet.cluster.models.Devices.Network;

/**
 * Tests ProtoFieldBindngTest class.
 */
public class ProtoFieldBindingTest {
    private static final FieldBinding networkToChainBinding;
    private static final FieldBinding chainToNetworkBinding;
    static {
        ListMultimap<Class<?>, FieldBinding> bindings =
            ProtoFieldBinding.createBindings(
            Network.class, "inbound_filter_id", DeleteAction.CLEAR,
            Chain.class, "network_ids", DeleteAction.CLEAR);
        networkToChainBinding = bindings.get(Network.class).get(0);
        chainToNetworkBinding = bindings.get(Chain.class).get(0);
    }

    private static final Commons.UUID uuid = createRandomUuidproto();
    private static final Commons.UUID conflictingUuid =
            createRandomUuidproto();
    private static final Commons.UUID otherUuid1 = createRandomUuidproto();
    private static final Commons.UUID otherUuid2 = createRandomUuidproto();
    private static final Commons.UUID otherUuid3 = createRandomUuidproto();

    private static final String CHAIN_NAME = "test_chain";
    private static final String NETWORK_NAME = "test_network";

    private static Commons.UUID createRandomUuidproto() {
        UUID uuid = UUID.randomUUID();
        return Commons.UUID.newBuilder().setMsb(uuid.getMostSignificantBits())
                                        .setLsb(uuid.getLeastSignificantBits())
                                        .build();
    }

    @Test
    public void testProtoBindingWithScalarRefType() {
        ListMultimap<Class<?>, FieldBinding> bindings =
                ProtoFieldBinding.createBindings(Network.class,
                                                 "inbound_filter_id",
                                                 DeleteAction.CLEAR,
                                                 Chain.class,
                                                 "network_ids",
                                                 DeleteAction.CASCADE);
        Collection<FieldBinding> networkBindings = bindings.get(Network.class);
        assertEquals(1, networkBindings.size());
        FieldBinding networkBinding = networkBindings.iterator().next();
        assertEquals(DeleteAction.CLEAR, networkBinding.onDeleteThis());

        Collection<FieldBinding> chainBindings = bindings.get(Chain.class);
        assertEquals(1, chainBindings.size());
        FieldBinding chainBinding = chainBindings.iterator().next();
        assertEquals(DeleteAction.CASCADE, chainBinding.onDeleteThis());
    }

    @Test
    public void testCreateBindingWithNoBackRef() {
        ProtoFieldBinding.createBindings(Network.class,
                                         "inbound_filter_id",
                                         DeleteAction.CLEAR,
                                         Chain.class,
                                         null,    // No back-ref.
                                         DeleteAction.CLEAR);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testProtoBindingForClassWithNoId() throws Exception {
        ProtoFieldBinding.createBindings(Network.class,
                                         null,
                                         DeleteAction.CASCADE,
                                         Devices.VtepBinding.class,
                                         "network_id",
                                         DeleteAction.CLEAR);
        fail("Should not allow binding of class with no id field.");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testProtoBindingWithUnrecognizedFieldName() throws Exception {
        ProtoFieldBinding.createBindings(Network.class,
                                         "no_such_field",
                                         DeleteAction.CLEAR,
                                         Devices.Port.class,
                                         "network_id",
                                         DeleteAction.CLEAR);
        fail("Should not allow binding with unrecognized field name.");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testProtoBindingWithWrongScalarRefType() throws Exception {
        ProtoFieldBinding.createBindings(Network.class,
                                         "name",
                                         DeleteAction.CLEAR,
                                         Devices.Port.class,
                                         "network_id",
                                         DeleteAction.CLEAR);
        fail("Should not allow ref from String to UUID.");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testProtoBindingWithWrongListRefType() throws Exception {
        ProtoFieldBinding.createBindings(Devices.Port.class,
                                         null,
                                         DeleteAction.CLEAR,
                                         TestModels.FakeDevice.class,
                                         "port_ids",
                                         DeleteAction.CLEAR);
        fail("Should not allow ref from String to UUID.");
    }

    @Test
    public void testAddBackRefFromChainToNetwork() throws Exception {
        Chain chain = Chain.newBuilder().setName(CHAIN_NAME).build();
        Chain updatedChain = networkToChainBinding.addBackReference(
                chain, chain.getId(), uuid);
        assertEquals(CHAIN_NAME, updatedChain.getName());
        assertThat(updatedChain.getNetworkIdsList(), contains(uuid));
    }

    @Test
    public void testAddBackRefToChain() throws Exception {
        Network network = Network.newBuilder().setName(NETWORK_NAME).build();
        Network updatedNetwork = chainToNetworkBinding.addBackReference(
                network, network.getId(), uuid);
        assertEquals(NETWORK_NAME, updatedNetwork.getName());
        assertEquals(uuid, updatedNetwork.getInboundFilterId());
    }

    @Test(expected = ReferenceConflictException.class)
    /* Even if the referring ID is the same, we don't allow it.*/
    public void testAddOverwritingBackRefToChain() throws Exception {
        Network network = Network.newBuilder().setName(NETWORK_NAME)
                                              .setInboundFilterId(uuid)
                                              .build();
        chainToNetworkBinding.addBackReference(network, network.getId(), uuid);
    }

    @Test(expected = ReferenceConflictException.class)
    public void testAddConflictingBackRefToChain() throws Exception {
        Network network = Network.newBuilder()
                                 .setName(NETWORK_NAME)
                                 .setInboundFilterId(conflictingUuid)
                                 .build();
        chainToNetworkBinding.addBackReference(network, network.getId(), uuid);
    }

    @Test
    public void testClearBackRefToNetwork() throws Exception {
        Chain chain = Chain.newBuilder().setName(CHAIN_NAME)
                                        .addNetworkIds(uuid)
                                        .build();
        Chain updatedChain =
                networkToChainBinding.clearBackReference(chain, uuid);
        assertEquals(CHAIN_NAME, updatedChain.getName());
        assertTrue(updatedChain.getNetworkIdsList().isEmpty());
    }

    @Test
    public void testClearOnlySingleBackRefToNetwork() throws Exception {
        Chain chain = Chain.newBuilder().setName(CHAIN_NAME)
                                        .addNetworkIds(uuid)
                                        .addNetworkIds(otherUuid1)
                                        .addNetworkIds(otherUuid2)
                                        .addNetworkIds(uuid)
                                        .addNetworkIds(otherUuid3)
                                        .build();
        Chain updatedChain =
                networkToChainBinding.clearBackReference(chain, uuid);
        assertEquals(CHAIN_NAME, updatedChain.getName());
        // Should delete only the first occurrence of the ID.
        assertEquals(4, updatedChain.getNetworkIdsList().size());
    }

    @Test
    public void testClearBackRefToChain() throws Exception {
        Network network = Network.newBuilder().setName(NETWORK_NAME)
                                              .setInboundFilterId(uuid)
                                              .build();
        Network updatedNetwork =
                chainToNetworkBinding.clearBackReference(network, uuid);
        assertEquals(NETWORK_NAME, updatedNetwork.getName());
        assertFalse(updatedNetwork.hasInboundFilterId());
    }

    @Test
    public void testClearMissingBackRefToNetwork() throws Exception {
        Chain chain = Chain.newBuilder().setName(CHAIN_NAME)
                                        .addNetworkIds(otherUuid1)
                                        .addNetworkIds(otherUuid2)
                                        .addNetworkIds(otherUuid3)
                                        .build();
        Chain updatedChain =
            networkToChainBinding.clearBackReference(chain, uuid);
        assertEquals(chain, updatedChain);
    }

    @Test
    public void testClearConflictingBackRefToChain() throws Exception {
        Network network = Network.newBuilder()
                                 .setName(NETWORK_NAME)
                                 .setInboundFilterId(conflictingUuid)
                                 .build();
        Network updatedNetwork =
                chainToNetworkBinding.clearBackReference(network, uuid);
        assertEquals(NETWORK_NAME, updatedNetwork.getName());
        assertEquals(conflictingUuid, updatedNetwork.getInboundFilterId());
    }

    @Test
    public void testClearMissingBackRefToChain() throws Exception {
        Network network = Network.newBuilder().setName(NETWORK_NAME).build();
        Network updatedNetwork =
                chainToNetworkBinding.clearBackReference(network, uuid);
        assertEquals(NETWORK_NAME, updatedNetwork.getName());
        assertFalse(updatedNetwork.hasInboundFilterId());
    }

    @Test
    public void testEmptyScalarFwdReferenceToChainAsEmptyList() {
        Network network = Network.newBuilder().setName(NETWORK_NAME).build();
        List<Object> fwdRefs =
                networkToChainBinding.getFwdReferenceAsList(network);
        assertTrue(fwdRefs.isEmpty());
    }

    @Test
    public void testFwdReferenceToChainAsList() throws Exception {
        Network network = Network.newBuilder().setName(NETWORK_NAME)
                                              .setInboundFilterId(uuid)
                                              .build();
        List<Object> fwdRefs =
                networkToChainBinding.getFwdReferenceAsList(network);
        assertEquals(1, fwdRefs.size());
        assertTrue(fwdRefs.contains(uuid));
    }

    @Test
    public void testEmptyFwdReferenceToNetworksAsEmptyList() {
        Chain chain = Chain.newBuilder().setName(CHAIN_NAME).build();
        List<Object> fwdRefs =
                chainToNetworkBinding.getFwdReferenceAsList(chain);
        assertTrue(fwdRefs.isEmpty());
    }

    @Test
    public void testFwdReferenceToNetworksAsList() throws Exception {
        Chain chain = Chain.newBuilder().setName(CHAIN_NAME)
                                        .addNetworkIds(otherUuid1)
                                        .addNetworkIds(otherUuid2)
                                        .addNetworkIds(otherUuid3)
                                        .build();
        List<Object> fwdRefs =
                chainToNetworkBinding.getFwdReferenceAsList(chain);
        assertEquals(3, fwdRefs.size());
        assertTrue(fwdRefs.contains(otherUuid1));
        assertTrue(fwdRefs.contains(otherUuid2));
        assertTrue(fwdRefs.contains(otherUuid3));
    }
}
