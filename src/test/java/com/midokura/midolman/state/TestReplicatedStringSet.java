package com.midokura.midolman.state;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.NoChildrenForEphemeralsException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TestReplicatedStringSet {

    ReplicatedStringSet stringSet;
    Directory setDir;
    Set<String> testStrings;
    Set<String> emptySet = Collections.emptySet();

    @Before
    public void setUp() throws NoNodeException, NodeExistsException,
            NoChildrenForEphemeralsException {
        Directory dir = new MockDirectory();
        dir.add("/one", null, CreateMode.PERSISTENT);
        dir.add("/one/two", null, CreateMode.PERSISTENT);
        setDir = dir.getSubDirectory("/one/two");
        stringSet = new ReplicatedStringSet(setDir);
        testStrings = new HashSet<String>();
        testStrings.add("foo");
        testStrings.add("bar");
        testStrings.add("pie");
    }

    @Test
    public void testStartEmpty() throws NoNodeException {
        stringSet.start();
        Assert.assertEquals(emptySet, stringSet.getStrings());
        stringSet.stop();
        Assert.assertEquals(emptySet, stringSet.getStrings());
    }

    @Test
    public void testAddToDirectoryThenStart() throws NoNodeException,
            NodeExistsException, NoChildrenForEphemeralsException {
        for (String str : testStrings) {
            setDir.add("/" + str, null, CreateMode.PERSISTENT);
        }
        stringSet.start();
        Assert.assertEquals(testStrings, stringSet.getStrings());
    }

    @Test
    public void testStartThenAddToDirectory() throws NoNodeException,
            NodeExistsException, NoChildrenForEphemeralsException {
        stringSet.start();
        for (String str : testStrings) {
            setDir.add("/" + str, null, CreateMode.PERSISTENT);
        }
        Assert.assertEquals(testStrings, stringSet.getStrings());
    }

    @Test
    public void testStartThenAdd() throws NoNodeException, NodeExistsException,
            NoChildrenForEphemeralsException {
        stringSet.start();
        stringSet.add(testStrings);
        Assert.assertEquals(testStrings, stringSet.getStrings());
    }

    @Test
    public void testStartThenAddDelete() throws NoNodeException, 
            NodeExistsException, NoChildrenForEphemeralsException{
        stringSet.start();
        stringSet.add(testStrings);
        Assert.assertEquals(testStrings, stringSet.getStrings());
        testStrings.remove("foo");
        stringSet.remove("foo");
        Assert.assertEquals(testStrings, stringSet.getStrings());
    }

    @Test
    public void testStartThenAddDeleteToDirectory() throws NoNodeException, 
            NodeExistsException, NoChildrenForEphemeralsException{
        stringSet.start();
        for (String str : testStrings) {
            setDir.add("/" + str, null, CreateMode.PERSISTENT);
        }
        Assert.assertEquals(testStrings, stringSet.getStrings());
        testStrings.remove("foo");
        setDir.delete("foo");
        Assert.assertEquals(testStrings, stringSet.getStrings());
    }

    @Test(expected=NodeExistsException.class)
    public void testAddStringExists() throws NoNodeException,
        NoChildrenForEphemeralsException, NodeExistsException {
        stringSet.start();
        try {
            stringSet.add("foo");
        }
        catch (NodeExistsException e) {
            Assert.fail();
        }
        stringSet.add("foo");
    }

    @Test(expected=NoNodeException.class)
    public void testRemoveStringDoesNotExist() throws NoNodeException,
        NoChildrenForEphemeralsException, NodeExistsException {
        stringSet.start();
        try {
            stringSet.add("foo");
        }
        catch (NoNodeException e) {
            Assert.fail();
        }
        stringSet.remove("bar");
    }

    private class MyWatcher implements ReplicatedStringSet.ChangeWatcher {
        Set<String> strings = new HashSet<String>();

        @Override
        public void process(Collection<String> addedStrings,
                Collection<String> removedStrings) {
            strings.addAll(addedStrings);
            strings.removeAll(removedStrings);
        }
    }

    @Test
    public void testChangeWatchers() throws NoNodeException,
            NodeExistsException, NoChildrenForEphemeralsException {
        MyWatcher watch1 = new MyWatcher();
        MyWatcher watch2 = new MyWatcher();
        stringSet.addWatcher(watch1);
        stringSet.addWatcher(watch2);
        stringSet.add(testStrings);
        // No notifications yet because the StringSet hasn't been started.
        Assert.assertEquals(emptySet, watch1.strings);
        Assert.assertEquals(emptySet, watch2.strings);
        stringSet.start();
        Assert.assertEquals(testStrings, watch1.strings);
        Assert.assertEquals(testStrings, watch2.strings);
        stringSet.remove("foo");
        testStrings.remove("foo");
        Assert.assertEquals(testStrings, watch1.strings);
        Assert.assertEquals(testStrings, watch2.strings);
        // Construct a set with just "bar" and "pie"
        Set<String> delStrings = new HashSet<String>();
        delStrings.add("bar");
        delStrings.add("pie");
        stringSet.remove(delStrings);
        // Now the watchers' sets should be empty.
        Assert.assertEquals(emptySet, watch1.strings);
        Assert.assertEquals(emptySet, watch2.strings);
        // Unregister the second watch
        stringSet.removeWatcher(watch2);
        testStrings.clear();
        testStrings.add("fox");
        stringSet.add("fox");
        Assert.assertEquals(testStrings, watch1.strings);
        Assert.assertEquals(emptySet, watch2.strings);
    }
}