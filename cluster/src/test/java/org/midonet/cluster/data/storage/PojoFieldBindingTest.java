/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage;

import org.junit.Test;

import org.midonet.cluster.data.storage.FieldBinding.DeleteAction;
import org.midonet.cluster.data.storage.ZookeeperObjectMapperTest.NoIdField;
import org.midonet.cluster.data.storage.ZookeeperObjectMapperTest.PojoBridge;
import org.midonet.cluster.data.storage.ZookeeperObjectMapperTest.PojoChain;
import org.midonet.cluster.data.storage.ZookeeperObjectMapperTest.PojoPort;
import org.midonet.cluster.data.storage.ZookeeperObjectMapperTest.PojoRule;

import static org.junit.Assert.fail;

/**
 * Tests PojoFieldBindngTest class.
 */
public class PojoFieldBindingTest {
    @Test
    public void testPojoBindingWithScalarRefType() {
        PojoFieldBinding.createBindings(
                PojoBridge.class, "inChainId", DeleteAction.CLEAR,
                PojoChain.class, "bridgeIds", DeleteAction.CLEAR);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPojoBindingForClassWithNoId() throws Exception {
        PojoFieldBinding.createBindings(
                NoIdField.class, "notId", DeleteAction.CLEAR,
                PojoBridge.class, "portIds", DeleteAction.CLEAR);
        fail("Should not allow binding of class with no id field.");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPojoBindingWithUnrecognizedFieldName() throws Exception {
        PojoFieldBinding.createBindings(
                PojoBridge.class, "noSuchField", DeleteAction.CLEAR,
                PojoPort.class, "bridgeId", DeleteAction.CLEAR);
        fail("Should not allow binding with unrecognized field name.");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPojoBindingWithWrongScalarRefType() throws Exception {
        PojoFieldBinding.createBindings(
                PojoBridge.class, "name", DeleteAction.CLEAR,
                PojoPort.class, "bridgeId", DeleteAction.CLEAR);
        fail("Should not allow ref from String to UUID.");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPojoBindingWithWrongListRefType() throws Exception {
        PojoFieldBinding.createBindings(
                PojoChain.class, "ruleIds", DeleteAction.CLEAR,
                PojoRule.class, "strings", DeleteAction.CLEAR);
        fail("Should not allow ref from List<String> to UUID.");
    }

}
