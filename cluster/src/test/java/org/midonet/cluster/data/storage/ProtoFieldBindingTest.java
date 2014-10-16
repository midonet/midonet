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
import static org.midonet.cluster.models.Devices.Bridge;
import static org.midonet.cluster.models.Devices.Chain;

/**
 * Tests ProtoFieldBindngTest class.
 */
public class ProtoFieldBindingTest {
    private static final FieldBinding bridgeToChainBinding;
    private static final FieldBinding chainToBridgeBinding;
    static {
        ListMultimap<Class<?>, FieldBinding> bindings =
            ProtoFieldBinding.createBindings(
            Bridge.class, "inbound_filter_id", DeleteAction.CLEAR,
            Chain.class, "bridge_ids", DeleteAction.CLEAR);
        bridgeToChainBinding = bindings.get(Bridge.class).get(0);
        chainToBridgeBinding = bindings.get(Chain.class).get(0);
    }

    private static final Commons.UUID uuid = createRandomUuidproto();
    private static final Commons.UUID conflictingUuid =
            createRandomUuidproto();
    private static final Commons.UUID otherUuid1 = createRandomUuidproto();
    private static final Commons.UUID otherUuid2 = createRandomUuidproto();
    private static final Commons.UUID otherUuid3 = createRandomUuidproto();

    private static Commons.UUID createRandomUuidproto() {
        UUID uuid = UUID.randomUUID();
        return Commons.UUID.newBuilder().setMsb(uuid.getMostSignificantBits())
                                        .setLsb(uuid.getLeastSignificantBits())
                                        .build();
    }

    @Test
    public void testProtoBindingWithScalarRefType() {
        ListMultimap<Class<?>, FieldBinding> bindings =
                ProtoFieldBinding.createBindings(Bridge.class,
                                                 "inbound_filter_id",
                                                 DeleteAction.CLEAR,
                                                 Chain.class,
                                                 "bridge_ids",
                                                 DeleteAction.CASCADE);
        Collection<FieldBinding> bridgeBindings = bindings.get(Bridge.class);
        assertEquals(1, bridgeBindings.size());
        FieldBinding bridgeBinding = bridgeBindings.iterator().next();
        assertEquals(DeleteAction.CLEAR, bridgeBinding.onDeleteThis());

        Collection<FieldBinding> chainBindings = bindings.get(Chain.class);
        assertEquals(1, chainBindings.size());
        FieldBinding chainBinding = chainBindings.iterator().next();
        assertEquals(DeleteAction.CASCADE, chainBinding.onDeleteThis());
    }

    @Test
    public void testCreateBindingWithNoBackRef() {
        ProtoFieldBinding.createBindings(Bridge.class,
                                         "inbound_filter_id",
                                         DeleteAction.CLEAR,
                                         Chain.class,
                                         null,    // No back-ref.
                                         DeleteAction.CLEAR);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testProtoBindingForClassWithNoId() throws Exception {
        ProtoFieldBinding.createBindings(Bridge.class,
                                         null,
                                         DeleteAction.CASCADE,
                                         Devices.VtepBinding.class,
                                         "network_id",
                                         DeleteAction.CLEAR);
        fail("Should not allow binding of class with no id field.");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testProtoBindingWithUnrecognizedFieldName() throws Exception {
        ProtoFieldBinding.createBindings(Bridge.class,
                                         "no_such_field",
                                         DeleteAction.CLEAR,
                                         Devices.Port.class,
                                         "bridge_id",
                                         DeleteAction.CLEAR);
        fail("Should not allow binding with unrecognized field name.");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testProtoBindingWithWrongScalarRefType() throws Exception {
        ProtoFieldBinding.createBindings(Bridge.class,
                                         "name",
                                         DeleteAction.CLEAR,
                                         Devices.Port.class,
                                         "bridge_id",
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
    public void testAddBackRefFromChainToBridge() throws Exception {
        Chain chain = Chain.newBuilder().setName("test_chain").build();
        Chain updatedChain = bridgeToChainBinding.addBackReference(
                chain, chain.getId(), uuid);
        assertEquals("test_chain", updatedChain.getName());
        assertThat(updatedChain.getBridgeIdsList(), contains(uuid));
    }

    @Test
    public void testAddBackRefToChain() throws Exception {
        Bridge bridge = Bridge.newBuilder().setName("test_bridge").build();
        Bridge updatedBridge = chainToBridgeBinding.addBackReference(
                bridge, bridge.getId(), uuid);
        assertEquals("test_bridge", updatedBridge.getName());
        assertEquals(uuid, updatedBridge.getInboundFilterId());
    }

    @Test(expected = ReferenceConflictException.class)
    /* Even if the referring ID is the same, we don't allow it.*/
    public void testAddOverwritingBackRefToChain() throws Exception {
        Bridge bridge = Bridge.newBuilder().setName("test_bridge")
                                           .setInboundFilterId(uuid)
                                           .build();
        chainToBridgeBinding.addBackReference(bridge, bridge.getId(), uuid);
    }

    @Test(expected = ReferenceConflictException.class)
    public void testAddConflictingBackRefToChain() throws Exception {
        Bridge bridge = Bridge.newBuilder().setName("test_bridge")
                                           .setInboundFilterId(conflictingUuid)
                                           .build();
        chainToBridgeBinding.addBackReference(bridge, bridge.getId(),  uuid);
    }

    @Test
    public void testClearBackRefToBridge() throws Exception {
        Chain chain = Chain.newBuilder().setName("test_chain")
                                        .addBridgeIds(uuid)
                                        .build();
        Chain updatedChain =
                bridgeToChainBinding.clearBackReference(chain, uuid);
        assertEquals("test_chain", updatedChain.getName());
        assertTrue(updatedChain.getBridgeIdsList().isEmpty());
    }

    @Test
    public void testClearOnlySingleBackRefToBridge() throws Exception {
        Chain chain = Chain.newBuilder().setName("test_chain")
                                        .addBridgeIds(uuid)
                                        .addBridgeIds(otherUuid1)
                                        .addBridgeIds(otherUuid2)
                                        .addBridgeIds(uuid)
                                        .addBridgeIds(otherUuid3)
                                        .build();
        Chain updatedChain =
                bridgeToChainBinding.clearBackReference(chain, uuid);
        assertEquals("test_chain", updatedChain.getName());
        // Should delete only the first occurrence of the ID.
        assertEquals(4, updatedChain.getBridgeIdsList().size());
    }

    @Test
    public void testClearBackRefToChain() throws Exception {
        Bridge bridge = Bridge.newBuilder().setName("test_bridge")
                                           .setInboundFilterId(uuid)
                                           .build();
        Bridge updatedBridge =
                chainToBridgeBinding.clearBackReference(bridge, uuid);
        assertEquals("test_bridge", updatedBridge.getName());
        assertFalse(updatedBridge.hasInboundFilterId());
    }

    @Test
    public void testClearMissingBackRefToBridge() throws Exception {
        Chain chain = Chain.newBuilder().setName("test_chain")
                                        .addBridgeIds(otherUuid1)
                                        .addBridgeIds(otherUuid2)
                                        .addBridgeIds(otherUuid3)
                                        .build();
        Chain updatedChain =
            bridgeToChainBinding.clearBackReference(chain, uuid);
        assertEquals(chain, updatedChain);
    }

    @Test
    public void testClearConflictingBackRefToChain() throws Exception {
        Bridge bridge = Bridge.newBuilder().setName("test_bridge")
                                           .setInboundFilterId(conflictingUuid)
                                           .build();
        Bridge updatedBridge =
                chainToBridgeBinding.clearBackReference(bridge, uuid);
        assertEquals("test_bridge", updatedBridge.getName());
        assertEquals(conflictingUuid, updatedBridge.getInboundFilterId());
    }

    @Test
    public void testClearMissingBackRefToChain() throws Exception {
        Bridge bridge = Bridge.newBuilder().setName("test_bridge").build();
        Bridge updatedBridge =
                chainToBridgeBinding.clearBackReference(bridge, uuid);
        assertEquals("test_bridge", updatedBridge.getName());
        assertFalse(updatedBridge.hasInboundFilterId());
    }

    @Test
    public void testEmptyScalarFwdReferenceToChainAsEmptyList() {
        Bridge bridge = Bridge.newBuilder().setName("test_bridge").build();
        List<Object> fwdRefs =
                bridgeToChainBinding.getFwdReferenceAsList(bridge);
        assertTrue(fwdRefs.isEmpty());
    }

    @Test
    public void testFwdReferenceToChainAsList() throws Exception {
        Bridge bridge = Bridge.newBuilder().setName("test_bridge")
                                           .setInboundFilterId(uuid)
                                           .build();
        List<Object> fwdRefs =
                bridgeToChainBinding.getFwdReferenceAsList(bridge);
        assertEquals(1, fwdRefs.size());
        assertTrue(fwdRefs.contains(uuid));
    }

    @Test
    public void testEmptyFwdReferenceToBridgesAsEmptyList() {
        Chain chain = Chain.newBuilder().setName("test_chain").build();
        List<Object> fwdRefs =
                chainToBridgeBinding.getFwdReferenceAsList(chain);
        assertTrue(fwdRefs.isEmpty());
    }

    @Test
    public void testFwdReferenceToBridgesAsList() throws Exception {
        Chain chain = Chain.newBuilder().setName("test_chain")
                                        .addBridgeIds(otherUuid1)
                                        .addBridgeIds(otherUuid2)
                                        .addBridgeIds(otherUuid3)
                                        .build();
        List<Object> fwdRefs =
                chainToBridgeBinding.getFwdReferenceAsList(chain);
        assertEquals(3, fwdRefs.size());
        assertTrue(fwdRefs.contains(otherUuid1));
        assertTrue(fwdRefs.contains(otherUuid2));
        assertTrue(fwdRefs.contains(otherUuid3));
    }
}
