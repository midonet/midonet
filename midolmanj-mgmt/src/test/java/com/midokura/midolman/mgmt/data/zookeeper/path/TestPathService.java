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

@RunWith(MockitoJUnitRunner.class)
public class TestPathService {

    private PathService testObject;

    // Set answer to RETURNS_SMART_NULLS since the methods defined for this
    // mock all return String, and it should return "" instead of null for the
    // unstubbed methods.
    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private PathBuilder pathBuilder;

    @Before
    public void setUp() {
        testObject = new PathService(pathBuilder);
    }

    @Test
    public void testGetInitialPaths() {

        // Set up
        doReturn("/foo/bar").when(pathBuilder).getBasePath();

        // Execute
        Set<String> pathSet = testObject.getInitialPaths();

        // Verify
        Assert.assertTrue(pathSet.contains("/foo"));
        Assert.assertTrue(pathSet.contains("/foo/bar"));

        // Verify that all the calls to create the init paths are executed.
        verify(pathBuilder).getAdRoutesPath();
        verify(pathBuilder).getRoutersPath();
        verify(pathBuilder).getBridgesPath();
        verify(pathBuilder).getPortsPath();
        verify(pathBuilder).getChainsPath();
        verify(pathBuilder).getFiltersPath();
        verify(pathBuilder).getGrePath();
        verify(pathBuilder).getPortSetsPath();
        verify(pathBuilder).getRulesPath();
        verify(pathBuilder).getRoutesPath();
        verify(pathBuilder).getBgpPath();
        verify(pathBuilder).getAdRoutesPath();
        verify(pathBuilder).getVRNPortLocationsPath();
        verify(pathBuilder).getVpnPath();
        verify(pathBuilder).getAgentPath();
        verify(pathBuilder).getAgentPortPath();
        verify(pathBuilder).getAgentVpnPath();
        verify(pathBuilder).getHostsPath();
        verify(pathBuilder).getPortGroupsPath();
        verify(pathBuilder).getTenantsPath();
    }
}
