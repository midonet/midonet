/*
 * @(#)TestPathService        1.6 11/12/20
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.path;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import com.midokura.midolman.state.ZkPathManager;

@RunWith(PowerMockRunner.class)
@PrepareForTest(PathHelper.class)
public class TestPathService {

    ZkPathManager managerMock = null;
    PathBuilder builderMock = null;
    final static String rootPath = "/base/path";
    final static String altRootPath = "/base/path_alt";

    private void setUpManagerMock() {
        Mockito.when(managerMock.getBasePath()).thenReturn(rootPath);
        Mockito.when(managerMock.getRoutersPath()).thenReturn(
                rootPath + "/routers");
        Mockito.when(managerMock.getBridgesPath()).thenReturn(
                rootPath + "/bridges");
        Mockito.when(managerMock.getPortsPath())
                .thenReturn(rootPath + "/ports");
        Mockito.when(managerMock.getChainsPath()).thenReturn(
                rootPath + "/chains");
        Mockito.when(managerMock.getGrePath()).thenReturn(rootPath + "/gre");
        Mockito.when(managerMock.getRulesPath())
                .thenReturn(rootPath + "/rules");
        Mockito.when(managerMock.getRoutesPath()).thenReturn(
                rootPath + "/routes");
        Mockito.when(managerMock.getBgpPath()).thenReturn(rootPath + "/bgp");
        Mockito.when(managerMock.getAdRoutesPath()).thenReturn(
                rootPath + "/ad_routes");
        Mockito.when(managerMock.getVRNPortLocationsPath()).thenReturn(
                rootPath + "/vrn_port_locations");
        Mockito.when(managerMock.getVpnPath()).thenReturn(rootPath + "/vpn");
        Mockito.when(managerMock.getAgentPath())
                .thenReturn(rootPath + "/agent");
        Mockito.when(managerMock.getAgentPortPath()).thenReturn(
                rootPath + "/agent_port");
        Mockito.when(managerMock.getAgentVpnPath()).thenReturn(
                rootPath + "/agent_vpn");
        Mockito.when(managerMock.getHostsPath())
                .thenReturn(rootPath + "/hosts");
        Mockito.when(managerMock.getPortSetsPath()).thenReturn(
                rootPath + "/port_sets");
    }

    private void setUpBuilderMock() {
        Mockito.when(builderMock.getBasePath()).thenReturn(altRootPath);
        Mockito.when(builderMock.getTenantsPath()).thenReturn(
                altRootPath + "/tenants");
        Mockito.when(builderMock.getRoutersPath()).thenReturn(
                altRootPath + "/routers");
        Mockito.when(builderMock.getBridgesPath()).thenReturn(
                altRootPath + "/bridges");
        Mockito.when(builderMock.getPortsPath()).thenReturn(
                altRootPath + "/ports");
        Mockito.when(builderMock.getChainsPath()).thenReturn(
                altRootPath + "/chains");
        Mockito.when(builderMock.getVifsPath()).thenReturn(
                altRootPath + "/vifs");
    }

    @Before
    public void setUp() throws Exception {
        managerMock = Mockito.mock(ZkPathManager.class);
        builderMock = Mockito.mock(PathBuilder.class);
        setUpManagerMock();
        setUpBuilderMock();
    }

    @Test
    public void testGetInitialPaths() throws Exception {
        List<String> subPaths = new ArrayList<String>();
        subPaths.add("/base");
        subPaths.add(rootPath);
        List<String> subAltPaths = new ArrayList<String>();
        subPaths.add("/base");
        subPaths.add(altRootPath);

        PowerMockito.mockStatic(PathHelper.class);
        PowerMockito.when(PathHelper.getSubPaths(rootPath))
                .thenReturn(subPaths);
        PowerMockito.when(PathHelper.getSubPaths(altRootPath)).thenReturn(
                subAltPaths);

        PathService service = new PathService(managerMock, builderMock);
        Set<String> pathSet = service.getInitialPaths();

        String[] paths = pathSet.toArray(new String[pathSet.size()]);
        Assert.assertEquals(25, paths.length);
        Assert.assertEquals("/base", paths[0]);
        Assert.assertEquals(rootPath, paths[1]);
        Assert.assertEquals(rootPath + "/ad_routes", paths[2]);
        Assert.assertEquals(rootPath + "/agent", paths[3]);
        Assert.assertEquals(rootPath + "/agent_port", paths[4]);
        Assert.assertEquals(rootPath + "/agent_vpn", paths[5]);
        Assert.assertEquals(rootPath + "/bgp", paths[6]);
        Assert.assertEquals(rootPath + "/bridges", paths[7]);
        Assert.assertEquals(rootPath + "/chains", paths[8]);
        Assert.assertEquals(rootPath + "/gre", paths[9]);
        Assert.assertEquals(rootPath + "/hosts", paths[10]);
        Assert.assertEquals(rootPath + "/port_sets", paths[11]);
        Assert.assertEquals(rootPath + "/ports", paths[12]);
        Assert.assertEquals(rootPath + "/routers", paths[13]);
        Assert.assertEquals(rootPath + "/routes", paths[14]);
        Assert.assertEquals(rootPath + "/rules", paths[15]);
        Assert.assertEquals(rootPath + "/vpn", paths[16]);
        Assert.assertEquals(rootPath + "/vrn_port_locations", paths[17]);

        Assert.assertEquals(altRootPath, paths[18]);
        Assert.assertEquals(altRootPath + "/bridges", paths[19]);
        Assert.assertEquals(altRootPath + "/chains", paths[20]);
        Assert.assertEquals(altRootPath + "/ports", paths[21]);
        Assert.assertEquals(altRootPath + "/routers", paths[22]);
        Assert.assertEquals(altRootPath + "/tenants", paths[23]);
        Assert.assertEquals(altRootPath + "/vifs", paths[24]);

    }
}
