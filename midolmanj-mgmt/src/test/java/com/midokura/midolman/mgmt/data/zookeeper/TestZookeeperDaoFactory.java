/*
 * @(#)TestZookeeperDaoFactory        1.6 11/11/23
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import com.midokura.midolman.mgmt.config.AppConfig;
import com.midokura.midolman.mgmt.config.InvalidConfigException;
import com.midokura.midolman.mgmt.data.DaoInitializationException;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkConnection;

@RunWith(PowerMockRunner.class)
@PrepareForTest(ZooKeeperDaoFactory.class)
public class TestZookeeperDaoFactory {

    AppConfig configMock = null;
    ZkConnection zkConnMock = null;
    Directory directoryMock = null;

    static final String defaultZkConnString = "test.com:2818";
    static final int defaultZkTimeout = 1;
    static final String defaultZkRootPath = "/testZkRootPath";
    static final String defaultZkMgmtRootPath = "/testZkMgmtRootPath";

    @Before
    public void setUp() throws Exception {
        configMock = PowerMockito.mock(AppConfig.class);
        zkConnMock = PowerMockito.mock(ZkConnection.class);
        directoryMock = PowerMockito.mock(Directory.class);
    }

    @Test
    public void testInitializeSuccess() throws Exception {

        // Setup
        PowerMockito.when(configMock.getZkRootPath()).thenReturn(
                defaultZkRootPath);
        PowerMockito.when(configMock.getZkMgmtRootPath()).thenReturn(
                defaultZkMgmtRootPath);
        PowerMockito.when(configMock.getZkConnectionString()).thenReturn(
                defaultZkConnString);
        PowerMockito.when(configMock.getZkTimeout()).thenReturn(
                defaultZkTimeout);
        PowerMockito.whenNew(ZkConnection.class)
                .withArguments(defaultZkConnString, defaultZkTimeout, null)
                .thenReturn(zkConnMock);
        PowerMockito.when(zkConnMock.getRootDirectory()).thenReturn(
                directoryMock);

        // Run
        ZooKeeperDaoFactory factory = new ZooKeeperDaoFactory(configMock);
        factory.getDirectory();

        // Check
        Assert.assertEquals(factory.getRootPath(), defaultZkRootPath);
        Assert.assertEquals(factory.getRootMgmtPath(), defaultZkMgmtRootPath);
        PowerMockito.verifyNew(ZkConnection.class, Mockito.times(1))
                .withArguments(defaultZkConnString, defaultZkTimeout, null);
        Mockito.verify(zkConnMock, Mockito.times(1)).open();
        Mockito.verify(zkConnMock, Mockito.times(1)).getRootDirectory();

        // Directory should be initialized. Times(1) should still remains in
        // tact.
        factory.getDirectory();
        PowerMockito.verifyNew(ZkConnection.class, Mockito.times(1))
                .withArguments(defaultZkConnString, defaultZkTimeout, null);
        Mockito.verify(zkConnMock, Mockito.times(1)).open();
        Mockito.verify(zkConnMock, Mockito.times(1)).getRootDirectory();
    }

    @Test(expected = DaoInitializationException.class)
    public void testInitializeBadConfig() throws Exception {
        // Setup
        PowerMockito.when(configMock.getZkRootPath()).thenThrow(
                new InvalidConfigException("Bad Config"));

        // Run
        new ZooKeeperDaoFactory(configMock);
    }

    @Test(expected = StateAccessException.class)
    public void testInitializeZkError() throws Exception {
        // Setup
        PowerMockito.doThrow(new Exception("Zk Error")).when(zkConnMock).open();

        // Run
        ZooKeeperDaoFactory factory = new ZooKeeperDaoFactory(configMock);
        factory.getDirectory();
    }
}
