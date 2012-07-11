/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.path;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import java.util.Set;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import com.midokura.midolman.state.ZkPathManager;

@RunWith(MockitoJUnitRunner.class)
public class TestPathService {

    private PathService testObject;

    // Set answer to RETURNS_SMART_NULLS since the methods defined for this
    // mock all return String, and it should return "" instead of null for the
    // unstubbed methods.
    @Mock(answer=Answers.RETURNS_SMART_NULLS)
    private ZkPathManager pathManager;

    @Mock(answer=Answers.RETURNS_SMART_NULLS)
    private PathBuilder pathBuilder;

    @Before
    public void setUp() {
        testObject = new PathService(pathManager, pathBuilder);
    }

    @Test
    public void testGetInitialPaths() {

        // Set up
        doReturn("/foo/bar").when(pathManager).getBasePath();
        doReturn("/foo/baz").when(pathBuilder).getBasePath();

        // Execute
        Set<String> pathSet = testObject.getInitialPaths();

        // Verify
        Assert.assertTrue(pathSet.contains("/foo"));
        Assert.assertTrue(pathSet.contains("/foo/bar"));
        Assert.assertTrue(pathSet.contains("/foo/baz"));

        // Verify that all the calls to create the init paths are executed.
        verify(pathManager).getAdRoutesPath();
        verify(pathManager).getRoutersPath();
        verify(pathManager).getBridgesPath();
        verify(pathManager).getPortsPath();
        verify(pathManager).getChainsPath();
        verify(pathManager).getFiltersPath();
        verify(pathManager).getGrePath();
        verify(pathManager).getPortSetsPath();
        verify(pathManager).getRulesPath();
        verify(pathManager).getRoutesPath();
        verify(pathManager).getBgpPath();
        verify(pathManager).getAdRoutesPath();
        verify(pathManager).getVRNPortLocationsPath();
        verify(pathManager).getVpnPath();
        verify(pathManager).getAgentPath();
        verify(pathManager).getAgentPortPath();
        verify(pathManager).getAgentVpnPath();
        verify(pathManager).getHostsPath();
        verify(pathBuilder).getTenantsPath();
        verify(pathBuilder).getRoutersPath();
    }
}
