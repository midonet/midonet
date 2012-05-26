/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.path;

import java.util.UUID;

import junit.framework.Assert;

import org.junit.Test;

public class TestPathBuilder {

    @Test
    public void testBuild() throws Exception {

        UUID uuid = UUID.randomUUID();
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

        // Tenant routers
        result = builder.getTenantRoutersPath(strId);
        expected = root + "/" + PathBuilder.TENANTS_PATH + "/" + strId + "/"
                + PathBuilder.ROUTERS_PATH;
        Assert.assertEquals(expected, result);

        // Tenant router
        result = builder.getTenantRouterPath(strId, uuid);
        expected = root + "/" + PathBuilder.TENANTS_PATH + "/" + strId + "/"
                + PathBuilder.ROUTERS_PATH + "/" + uuid;
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

        // Tenant bridges
        result = builder.getTenantBridgesPath(strId);
        expected = root + "/" + PathBuilder.TENANTS_PATH + "/" + strId + "/"
                + PathBuilder.BRIDGES_PATH;
        Assert.assertEquals(expected, result);

        // Tenant bridge
        result = builder.getTenantBridgePath(strId, uuid);
        expected = root + "/" + PathBuilder.TENANTS_PATH + "/" + strId + "/"
                + PathBuilder.BRIDGES_PATH + "/" + uuid;
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

        // VIFs
        result = builder.getVifsPath();
        expected = root + "/" + PathBuilder.VIFS_PATH;
        Assert.assertEquals(expected, result);

        // VIF
        result = builder.getVifPath(uuid);
        expected = root + "/" + PathBuilder.VIFS_PATH + "/" + uuid;
        Assert.assertEquals(expected, result);

        // Routers
        result = builder.getRoutersPath();
        expected = root + "/" + PathBuilder.ROUTERS_PATH;
        Assert.assertEquals(expected, result);

        // Router
        result = builder.getRouterPath(uuid);
        expected = root + "/" + PathBuilder.ROUTERS_PATH + "/" + uuid;
        Assert.assertEquals(expected, result);

        // Tenant chains
        result = builder.getTenantChainsPath(strId);
        expected = root + "/" + PathBuilder.TENANTS_PATH + "/" + strId + "/"
                + PathBuilder.CHAINS_PATH;
        Assert.assertEquals(expected, result);

        // Tenant chain
        result = builder.getTenantChainPath(strId, uuid);
        expected = root + "/" + PathBuilder.TENANTS_PATH + "/" + strId + "/"
                + PathBuilder.CHAINS_PATH + "/" + uuid;
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

        // Bridges
        result = builder.getBridgesPath();
        expected = root + "/" + PathBuilder.BRIDGES_PATH;
        Assert.assertEquals(expected, result);

        // Bridges
        result = builder.getBridgePath(uuid);
        expected = root + "/" + PathBuilder.BRIDGES_PATH + "/" + uuid;
        Assert.assertEquals(expected, result);

        // Ports
        result = builder.getPortsPath();
        expected = root + "/" + PathBuilder.PORTS_PATH;
        Assert.assertEquals(expected, result);

        // Port
        result = builder.getPortPath(uuid);
        expected = root + "/" + PathBuilder.PORTS_PATH + "/" + uuid;
        Assert.assertEquals(expected, result);

        // Chains
        result = builder.getChainsPath();
        expected = root + "/" + PathBuilder.CHAINS_PATH;
        Assert.assertEquals(expected, result);

        // Chain
        result = builder.getChainPath(uuid);
        expected = root + "/" + PathBuilder.CHAINS_PATH + "/" + uuid;
        Assert.assertEquals(expected, result);

    }
}
