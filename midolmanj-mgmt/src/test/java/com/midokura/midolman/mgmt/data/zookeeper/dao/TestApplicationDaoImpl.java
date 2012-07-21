/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.dao;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import java.util.Set;
import java.util.TreeSet;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import com.midokura.midolman.mgmt.data.zookeeper.dao.ApplicationDaoImpl;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathService;
import com.midokura.midolman.state.StatePathExistsException;
import com.midokura.midolman.state.ZkManager;

@RunWith(MockitoJUnitRunner.class)
public class TestApplicationDaoImpl {

    private ApplicationDaoImpl testObject;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private ZkManager dao;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private PathService pathService;

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
        testObject = new ApplicationDaoImpl(dao, pathService);
    }

    @Test
    public void testInitializeIdempotence() throws Exception {

        doReturn(testPaths).when(pathService).getInitialPaths();
        doThrow(StatePathExistsException.class).when(dao).addPersistent(
                testPath1, null);

        testObject.initialize();
    }
}
