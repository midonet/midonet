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
        String result = builder.build();
        Assert.assertEquals(expected, result);

        // Null -> empty root
        builder = new PathBuilder(null);
        result = builder.build();
        Assert.assertEquals(expected, result);

        // Just root - slash
        builder = new PathBuilder("/");
        result = builder.build();
        expected = "/";
        Assert.assertEquals("/", result);

        // Tenants
        builder = new PathBuilder(root);
        result = builder.tenants().build();
        expected = root + PathBuilder.TENANTS_PATH;
        Assert.assertEquals(expected, result);

        // Tenant
        builder = new PathBuilder(root);
        result = builder.tenants(strId).build();
        expected = root + PathBuilder.TENANTS_PATH + "/" + strId;
        Assert.assertEquals(expected, result);

        // Tenant routers
        builder = new PathBuilder(root);
        result = builder.tenants(strId).routers().build();
        expected = root + PathBuilder.TENANTS_PATH + "/" + strId
                + PathBuilder.ROUTERS_PATH;
        Assert.assertEquals(expected, result);

        // Tenant router
        builder = new PathBuilder(root);
        result = builder.tenants(strId).routers(uuid).build();
        expected = root + PathBuilder.TENANTS_PATH + "/" + strId
                + PathBuilder.ROUTERS_PATH + "/" + uuid;
        Assert.assertEquals(expected, result);

        // Tenant router names
        builder = new PathBuilder(root);
        result = builder.tenants(strId).routerNames().build();
        expected = root + PathBuilder.TENANTS_PATH + "/" + strId
                + PathBuilder.ROUTER_NAMES_PATH;
        Assert.assertEquals(expected, result);

        // Tenant router name
        builder = new PathBuilder(root);
        result = builder.tenants(strId).routerNames(testName).build();
        expected = root + PathBuilder.TENANTS_PATH + "/" + strId
                + PathBuilder.ROUTER_NAMES_PATH + "/" + testName;
        Assert.assertEquals(expected, result);

        // Tenant bridges
        builder = new PathBuilder(root);
        result = builder.tenants(strId).bridges().build();
        expected = root + PathBuilder.TENANTS_PATH + "/" + strId
                + PathBuilder.BRIDGES_PATH;
        Assert.assertEquals(expected, result);

        // Tenant bridge
        builder = new PathBuilder(root);
        result = builder.tenants(strId).bridges(uuid).build();
        expected = root + PathBuilder.TENANTS_PATH + "/" + strId
                + PathBuilder.BRIDGES_PATH + "/" + uuid;
        Assert.assertEquals(expected, result);

        // Tenant bridge names
        builder = new PathBuilder(root);
        result = builder.tenants(strId).bridgeNames().build();
        expected = root + PathBuilder.TENANTS_PATH + "/" + strId
                + PathBuilder.BRIDGE_NAMES_PATH;
        Assert.assertEquals(expected, result);

        // Tenant bridge name
        builder = new PathBuilder(root);
        result = builder.tenants(strId).bridgeNames(testName).build();
        expected = root + PathBuilder.TENANTS_PATH + "/" + strId
                + PathBuilder.BRIDGE_NAMES_PATH + "/" + testName;
        Assert.assertEquals(expected, result);

        // VIFs
        builder = new PathBuilder(root);
        result = builder.vifs().build();
        expected = root + PathBuilder.VIFS_PATH;
        Assert.assertEquals(expected, result);

        // VIF
        builder = new PathBuilder(root);
        result = builder.vifs(uuid).build();
        expected = root + PathBuilder.VIFS_PATH + "/" + uuid;
        Assert.assertEquals(expected, result);

        // Routers
        builder = new PathBuilder(root);
        result = builder.routers().build();
        expected = root + PathBuilder.ROUTERS_PATH;
        Assert.assertEquals(expected, result);

        // Router
        builder = new PathBuilder(root);
        result = builder.routers(uuid).build();
        expected = root + PathBuilder.ROUTERS_PATH + "/" + uuid;
        Assert.assertEquals(expected, result);

        // Router routers
        builder = new PathBuilder(root);
        result = builder.routers(uuid).routers().build();
        expected = root + PathBuilder.ROUTERS_PATH + "/" + uuid
                + PathBuilder.ROUTERS_PATH;
        Assert.assertEquals(expected, result);

        // Router router
        builder = new PathBuilder(root);
        result = builder.routers(uuid).routers(uuid).build();
        expected = root + PathBuilder.ROUTERS_PATH + "/" + uuid
                + PathBuilder.ROUTERS_PATH + "/" + uuid;
        Assert.assertEquals(expected, result);

        // Router tables
        builder = new PathBuilder(root);
        result = builder.routers(uuid).tables().build();
        expected = root + PathBuilder.ROUTERS_PATH + "/" + uuid
                + PathBuilder.TABLES_PATH;
        Assert.assertEquals(expected, result);

        // Router table
        builder = new PathBuilder(root);
        result = builder.routers(uuid).tables(testName).build();
        expected = root + PathBuilder.ROUTERS_PATH + "/" + uuid
                + PathBuilder.TABLES_PATH + "/" + testName;
        Assert.assertEquals(expected, result);

        // Router tables
        builder = new PathBuilder(root);
        result = builder.routers(uuid).tables(ChainTable.NAT).build();
        expected = root + PathBuilder.ROUTERS_PATH + "/" + uuid
                + PathBuilder.TABLES_PATH + "/" + ChainTable.NAT;
        Assert.assertEquals(expected, result);

        // Bridges
        builder = new PathBuilder(root);
        result = builder.bridges().build();
        expected = root + PathBuilder.BRIDGES_PATH;
        Assert.assertEquals(expected, result);

        // Bridges
        builder = new PathBuilder(root);
        result = builder.bridges(uuid).build();
        expected = root + PathBuilder.BRIDGES_PATH + "/" + uuid;
        Assert.assertEquals(expected, result);

        // Ports
        builder = new PathBuilder(root);
        result = builder.ports().build();
        expected = root + PathBuilder.PORTS_PATH;
        Assert.assertEquals(expected, result);

        // Port
        builder = new PathBuilder(root);
        result = builder.ports(uuid).build();
        expected = root + PathBuilder.PORTS_PATH + "/" + uuid;
        Assert.assertEquals(expected, result);

        // Chains
        builder = new PathBuilder(root);
        result = builder.chains().build();
        expected = root + PathBuilder.CHAINS_PATH;
        Assert.assertEquals(expected, result);

        // Chain
        builder = new PathBuilder(root);
        result = builder.chains(uuid).build();
        expected = root + PathBuilder.CHAINS_PATH + "/" + uuid;
        Assert.assertEquals(expected, result);

    }
}
