/*
 * @(#)TestChainTable        1.6 11/12/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.core;

import java.util.Arrays;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

public class TestChainTable {

    @Test
    public void testIsBuiltInTableName() throws Exception {

        boolean result = ChainTable.isBuiltInTableName("nat");
        Assert.assertTrue(result);

        result = ChainTable.isBuiltInTableName("nAt");
        Assert.assertTrue(result);

        result = ChainTable.isBuiltInTableName("");
        Assert.assertFalse(result);

        result = ChainTable.isBuiltInTableName("foo");
        Assert.assertFalse(result);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testIsBuiltInTableNameNullInput() throws Exception {
        ChainTable.isBuiltInTableName(null);
    }

    @Test
    public void testGetBuiltInChainNames() throws Exception {

        String[] names = ChainTable.getBuiltInChainNames(ChainTable.NAT);
        List<String> nameList = Arrays.asList(names);
        Assert.assertTrue(nameList.contains("pre_routing"));
        Assert.assertTrue(nameList.contains("post_routing"));
    }

    @Test
    public void testIsBuiltInChainName() throws Exception {

        boolean result = ChainTable.isBuiltInChainName(ChainTable.NAT,
                "pre_routing");
        Assert.assertTrue(result);

        result = ChainTable.isBuiltInChainName(ChainTable.NAT, "post_routing");
        Assert.assertTrue(result);

        result = ChainTable.isBuiltInChainName(ChainTable.NAT, "POST_routing");
        Assert.assertTrue(result);

        result = ChainTable.isBuiltInChainName(ChainTable.NAT, "");
        Assert.assertFalse(result);

        result = ChainTable.isBuiltInChainName(ChainTable.NAT, "foo");
        Assert.assertFalse(result);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testIsBuiltInChainNameNullInput() throws Exception {
        ChainTable.isBuiltInChainName(ChainTable.NAT, null);
    }

}
