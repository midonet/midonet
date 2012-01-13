/*
 * @(#)TestApplicationZkDao        1.6 11/12/20
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.util.Set;
import java.util.TreeSet;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com.midokura.midolman.mgmt.data.dao.ApplicationDao;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathService;
import com.midokura.midolman.state.StatePathExistsException;
import com.midokura.midolman.state.ZkManager;

public class TestApplicationZkDao {

    private ZkManager managerMock = null;
    private PathService pathMock = null;
    private static final String testPath1 = "/foo";
    private static final String testPath2 = "/foo/bar";
    private static final String testPath3 = "/foo/bar/baz";

    private static Set<String> testPaths = new TreeSet<String>();
    static {
        testPaths.add(testPath1);
        testPaths.add(testPath2);
        testPaths.add(testPath3);
    }

    @Before
    public void setUp() throws Exception {
        managerMock = Mockito.mock(ZkManager.class);
        pathMock = Mockito.mock(PathService.class);
        Mockito.when(pathMock.getInitialPaths()).thenReturn(testPaths);
    }

    @Test
    public void testInitializeSuccess() throws Exception {

        Mockito.when(managerMock.addPersistent(testPath1, null)).thenReturn(
                testPath1);
        Mockito.when(managerMock.addPersistent(testPath2, null)).thenReturn(
                testPath2);
        Mockito.when(managerMock.addPersistent(testPath3, null)).thenReturn(
                testPath3);

        ApplicationDao dao = new ApplicationZkDao(managerMock, pathMock);
        dao.initialize();

        Mockito.verify(managerMock, Mockito.times(1)).addPersistent(testPath1,
                null);
        Mockito.verify(managerMock, Mockito.times(1)).addPersistent(testPath2,
                null);
        Mockito.verify(managerMock, Mockito.times(1)).addPersistent(testPath3,
                null);

    }

    @Test
    public void testInitializeExistingPaths() throws Exception {
        Mockito.when(managerMock.addPersistent(testPath1, null)).thenThrow(
                new StatePathExistsException());
        Mockito.when(managerMock.addPersistent(testPath2, null)).thenThrow(
                new StatePathExistsException());
        Mockito.when(managerMock.addPersistent(testPath3, null)).thenThrow(
                new StatePathExistsException());

        ApplicationDao dao = new ApplicationZkDao(managerMock, pathMock);
        dao.initialize();

        Mockito.verify(managerMock, Mockito.times(1)).addPersistent(testPath1,
                null);
        Mockito.verify(managerMock, Mockito.times(1)).addPersistent(testPath2,
                null);
        Mockito.verify(managerMock, Mockito.times(1)).addPersistent(testPath3,
                null);

    }
}
