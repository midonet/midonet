/**
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage;

import java.util.Collection;
import java.util.ConcurrentModificationException;
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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.midonet.cluster.models.Devices.Bridge;
import static org.midonet.cluster.models.Devices.Chain;

/**
 * Tests ProtoFieldBindngTest class.
 */
public class ProtoFieldBindingTest {
    private static final ProtoFieldBinding bridgeToChainBinding =
            new ProtoFieldBinding(
                    Bridge.getDescriptor().findFieldByName("inbound_filter_id"),
                    Chain.getDefaultInstance(),
                    Chain.getDescriptor().findFieldByName("bridge_ids"),
                    DeleteAction.CLEAR);
    private static final ProtoFieldBinding chainToBridgeBinding =
            new ProtoFieldBinding(
                    Chain.getDescriptor().findFieldByName("bridge_ids"),
                    Bridge.getDefaultInstance(),
                    Bridge.getDescriptor().findFieldByName("inbound_filter_id"),
                    DeleteAction.CLEAR);

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
                ProtoFieldBinding.createBindings(Bridge.getDefaultInstance(),
                                                 "inbound_filter_id",
                                                 DeleteAction.CLEAR,
                                                 Chain.getDefaultInstance(),
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
        ProtoFieldBinding.createBindings(Bridge.getDefaultInstance(),
                                         "inbound_filter_id",
                                         DeleteAction.CLEAR,
                                         Chain.getDefaultInstance(),
                                         null,    // No back-ref.
                                         DeleteAction.CLEAR);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testProtoBindingForClassWithNoId() throws Exception {
        ProtoFieldBinding.createBindings(Bridge.getDefaultInstance(),
                                         null,
                                         DeleteAction.CASCADE,
                                         Devices.VtepBinding.getDefaultInstance(),
                                         "network_id",
                                         DeleteAction.CLEAR);
        fail("Should not allow binding of class with no id field.");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testProtoBindingWithUnrecognizedFieldName() throws Exception {
        ProtoFieldBinding.createBindings(Bridge.getDefaultInstance(),
                                         "no_such_field",
                                         DeleteAction.CLEAR,
                                         Devices.Port.getDefaultInstance(),
                                         "bridge_id",
                                         DeleteAction.CLEAR);
        fail("Should not allow binding with unrecognized field name.");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testProtoBindingWithWrongScalarRefType() throws Exception {
        ProtoFieldBinding.createBindings(Bridge.getDefaultInstance(),
                                         "name",
                                         DeleteAction.CLEAR,
                                         Devices.Port.getDefaultInstance(),
                                         "bridge_id",
                                         DeleteAction.CLEAR);
        fail("Should not allow ref from String to UUID.");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testProtoBindingWithWrongListRefType() throws Exception {
        ProtoFieldBinding.createBindings(Devices.Port.getDefaultInstance(),
                                         null,
                                         DeleteAction.CLEAR,
                                         TestModels.FakeDevice.getDefaultInstance(),
                                         "port_ids",
                                         DeleteAction.CLEAR);
        fail("Should not allow ref from String to UUID.");
    }

    @Test
    public void testAddBackRefFromChainToBridge() throws Exception {
        Chain chain = Chain.newBuilder().setName("test_chain").build();
        Chain updatedChain = bridgeToChainBinding.addBackReference(chain, uuid);
        assertEquals("test_chain", updatedChain.getName());
        assertThat(updatedChain.getBridgeIdsList(), contains(uuid));
    }

    @Test
    public void testAddBackRefToChain() throws Exception {
        Bridge bridge = Bridge.newBuilder().setName("test_bridge").build();
        Bridge updatedBridge =
                chainToBridgeBinding.addBackReference(bridge, uuid);
        assertEquals("test_bridge", updatedBridge.getName());
        assertEquals(uuid, updatedBridge.getInboundFilterId());
    }

    @Test(expected = ReferenceConflictException.class)
    /* Even if the referring ID is the same, we don't allow it.*/
    public void testAddOverwritingBackRefToChain() throws Exception {
        Bridge bridge = Bridge.newBuilder().setName("test_bridge")
                                           .setInboundFilterId(uuid)
                                           .build();
        chainToBridgeBinding.addBackReference(bridge, uuid);
    }

    @Test(expected = ReferenceConflictException.class)
    public void testAddConflictingBackRefToChain() throws Exception {
        Bridge bridge = Bridge.newBuilder().setName("test_bridge")
                                           .setInboundFilterId(conflictingUuid)
                                           .build();
        chainToBridgeBinding.addBackReference(bridge, uuid);
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

    @Test(expected = ConcurrentModificationException.class)
    public void testClearMissingBackRefToBridge() throws Exception {
        Chain chain = Chain.newBuilder().setName("test_chain")
                                        .addBridgeIds(otherUuid1)
                                        .addBridgeIds(otherUuid2)
                                        .addBridgeIds(otherUuid3)
                                        .build();
        bridgeToChainBinding.clearBackReference(chain, uuid);
    }

    @Test(expected = ConcurrentModificationException.class)
    public void testClearConflictingBackRefToChain() throws Exception {
        Bridge bridge = Bridge.newBuilder().setName("test_bridge")
                                           .setInboundFilterId(conflictingUuid)
                                           .build();
        Bridge updatedBridge =
                chainToBridgeBinding.clearBackReference(bridge, uuid);
        assertEquals("test_bridge", updatedBridge.getName());
        assertFalse(updatedBridge.hasInboundFilterId());
    }

    @Test(expected = ConcurrentModificationException.class)
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
