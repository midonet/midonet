/**
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage;

import org.junit.Test;

import org.midonet.cluster.data.storage.FieldBinding.DeleteAction;
import org.midonet.cluster.data.storage.ZookeeperObjectMapperTest.Bridge;
import org.midonet.cluster.data.storage.ZookeeperObjectMapperTest.Chain;
import org.midonet.cluster.data.storage.ZookeeperObjectMapperTest.NoIdField;
import org.midonet.cluster.data.storage.ZookeeperObjectMapperTest.Port;
import org.midonet.cluster.data.storage.ZookeeperObjectMapperTest.Rule;

import static org.junit.Assert.fail;

/**
 * Tests PojoFieldBindngTest class.
 */
public class PojoFieldBindingTest {
    @Test
    public void testPojoBindingWithScalarRefType() {
        PojoFieldBinding.createBindings(
                Bridge.class, "inChainId", DeleteAction.CLEAR,
                Chain.class, "bridgeIds", DeleteAction.CLEAR);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPojoBindingForClassWithNoId() throws Exception {
        PojoFieldBinding.createBindings(
                NoIdField.class, "notId", DeleteAction.CLEAR,
                Bridge.class, "portIds", DeleteAction.CLEAR);
        fail("Should not allow binding of class with no id field.");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPojoBindingWithUnrecognizedFieldName() throws Exception {
        PojoFieldBinding.createBindings(
                Bridge.class, "noSuchField", DeleteAction.CLEAR,
                Port.class, "bridgeId", DeleteAction.CLEAR);
        fail("Should not allow binding with unrecognized field name.");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPojoBindingWithWrongScalarRefType() throws Exception {
        PojoFieldBinding.createBindings(
                Bridge.class, "name", DeleteAction.CLEAR,
                Port.class, "bridgeId", DeleteAction.CLEAR);
        fail("Should not allow ref from String to UUID.");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPojoBindingWithWrongListRefType() throws Exception {
        PojoFieldBinding.createBindings(
                Chain.class, "ruleIds", DeleteAction.CLEAR,
                Rule.class, "strings", DeleteAction.CLEAR);
        fail("Should not allow ref from List<String> to UUID.");
    }

}
