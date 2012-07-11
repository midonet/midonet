/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.path;

import junit.framework.Assert;

import org.junit.Test;

public class TestPathBuilder {

    @Test
    public void testBuild() throws Exception {

        String strId = "foo";
        String testName = "bar";
        String root = "/root";
        String expected = "";

        // Empty string
        PathBuilder builder = new PathBuilder("");
        String result = builder.getBasePath();
        Assert.assertEquals(expected, result);

        // Null -> empty root
        builder = new PathBuilder(null);
        result = builder.getBasePath();
        Assert.assertEquals(expected, result);

        // Just root - slash
        builder = new PathBuilder("/");
        result = builder.getBasePath();
        expected = "/";
        Assert.assertEquals("/", result);

        // Tenants
        builder = new PathBuilder(root);
        result = builder.getTenantsPath();
        expected = root + "/" + PathBuilder.TENANTS_PATH;
        Assert.assertEquals(expected, result);

        // Tenant
        result = builder.getTenantPath(strId);
        expected = root + "/" + PathBuilder.TENANTS_PATH + "/" + strId;
        Assert.assertEquals(expected, result);

        // Tenant router names
        result = builder.getTenantRouterNamesPath(strId);
        expected = root + "/" + PathBuilder.TENANTS_PATH + "/" + strId + "/"
                + PathBuilder.ROUTER_NAMES_PATH;
        Assert.assertEquals(expected, result);

        // Tenant router name
        result = builder.getTenantRouterNamePath(strId, testName);
        expected = root + "/" + PathBuilder.TENANTS_PATH + "/" + strId + "/"
                + PathBuilder.ROUTER_NAMES_PATH + "/" + testName;
        Assert.assertEquals(expected, result);

        // Tenant bridge names
        result = builder.getTenantBridgeNamesPath(strId);
        expected = root + "/" + PathBuilder.TENANTS_PATH + "/" + strId + "/"
                + PathBuilder.BRIDGE_NAMES_PATH;
        Assert.assertEquals(expected, result);

        // Tenant bridge name
        result = builder.getTenantBridgeNamePath(strId, testName);
        expected = root + "/" + PathBuilder.TENANTS_PATH + "/" + strId + "/"
                + PathBuilder.BRIDGE_NAMES_PATH + "/" + testName;
        Assert.assertEquals(expected, result);

        // Tenant chain names
        result = builder.getTenantChainNamesPath(strId);
        expected = root + "/" + PathBuilder.TENANTS_PATH + "/" + strId + "/"
                + PathBuilder.CHAIN_NAMES_PATH;
        Assert.assertEquals(expected, result);

        // Tenant chain name
        result = builder.getTenantChainNamePath(strId, testName);
        expected = root + "/" + PathBuilder.TENANTS_PATH + "/" + strId + "/"
                + PathBuilder.CHAIN_NAMES_PATH + "/" + testName;
        Assert.assertEquals(expected, result);

    }
}
