/*
 * @(#)TestPathBuilder        1.6 11/12/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.path;

import java.util.UUID;

import junit.framework.Assert;

import org.junit.Test;

import com.midokura.midolman.mgmt.rest_api.core.ChainTable;

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

        // Router routers
        result = builder.getRouterRoutersPath(uuid);
        expected = root + "/" + PathBuilder.ROUTERS_PATH + "/" + uuid + "/"
                + PathBuilder.ROUTERS_PATH;
        Assert.assertEquals(expected, result);

        // Router router
        result = builder.getRouterRouterPath(uuid, uuid);
        expected = root + "/" + PathBuilder.ROUTERS_PATH + "/" + uuid + "/"
                + PathBuilder.ROUTERS_PATH + "/" + uuid;
        Assert.assertEquals(expected, result);

        // Router tables
        result = builder.getRouterTablesPath(uuid);
        expected = root + "/" + PathBuilder.ROUTERS_PATH + "/" + uuid + "/"
                + PathBuilder.TABLES_PATH;
        Assert.assertEquals(expected, result);

        // Router table
        result = builder.getRouterTablePath(uuid, ChainTable.NAT);
        expected = root + "/" + PathBuilder.ROUTERS_PATH + "/" + uuid + "/"
                + PathBuilder.TABLES_PATH + "/" + ChainTable.NAT;
        Assert.assertEquals(expected, result);

        // Router table chains
        result = builder.getRouterTableChainsPath(uuid, ChainTable.NAT);
        expected = root + "/" + PathBuilder.ROUTERS_PATH + "/" + uuid + "/"
                + PathBuilder.TABLES_PATH + "/" + ChainTable.NAT + "/"
                + PathBuilder.CHAINS_PATH;
        Assert.assertEquals(expected, result);

        // Router table chain
        result = builder.getRouterTableChainPath(uuid, ChainTable.NAT, uuid);
        expected = root + "/" + PathBuilder.ROUTERS_PATH + "/" + uuid + "/"
                + PathBuilder.TABLES_PATH + "/" + ChainTable.NAT + "/"
                + PathBuilder.CHAINS_PATH + "/" + uuid;
        Assert.assertEquals(expected, result);

        // Router table chain names
        result = builder.getRouterTableChainNamesPath(uuid, ChainTable.NAT);
        expected = root + "/" + PathBuilder.ROUTERS_PATH + "/" + uuid + "/"
                + PathBuilder.TABLES_PATH + "/" + ChainTable.NAT + "/"
                + PathBuilder.CHAIN_NAMES_PATH;
        Assert.assertEquals(expected, result);

        // Router table chain name
        result = builder.getRouterTableChainNamePath(uuid, ChainTable.NAT,
                testName);
        expected = root + "/" + PathBuilder.ROUTERS_PATH + "/" + uuid + "/"
                + PathBuilder.TABLES_PATH + "/" + ChainTable.NAT + "/"
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
