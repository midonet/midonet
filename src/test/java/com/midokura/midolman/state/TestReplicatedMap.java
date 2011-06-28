package com.midokura.midolman.state;

import java.util.HashMap;
import java.util.Map;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.NoChildrenForEphemeralsException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.junit.Assert;

import org.junit.Before;
import org.junit.Test;

public class TestReplicatedMap {

    private class ReplicatedStringMap extends ReplicatedMap<String, String> {

        public ReplicatedStringMap(Directory dir) {
            super(dir);
        }

        @Override
        protected String encodeKey(String key) {
            return key;
        }

        @Override
        protected String decodeKey(String str) {
            return str;
        }

        @Override
        protected String encodeValue(String value) {
            return value;
        }

        @Override
        protected String decodeValue(String str) {
            return str;
        }
    }

    private class Location {
        int a;
        int b;
        boolean free;
    }

    private class ReplicatedStringToLocationMap extends 
            ReplicatedMap<String, Location> {

        public ReplicatedStringToLocationMap(Directory dir) {
            super(dir);
        }

        @Override
        protected String encodeKey(String key) {
            return key;
        }

        @Override
        protected String decodeKey(String str) {
            return str;
        }

        @Override
        protected String encodeValue(Location value) {
            StringBuilder builder = new StringBuilder();
            builder.append(value.a).append(",");
            builder.append(value.b).append(",");
            builder.append(value.free);
            return builder.toString();
        }

        @Override
        protected Location decodeValue(String str) {
            String[] parts = str.split(",");
            Location loc = new Location();
            loc.a = Integer.parseInt(parts[0]);
            loc.b = Integer.parseInt(parts[1]);
            loc.free = Boolean.parseBoolean(parts[2]);
            return loc;
        }
    }

    Directory mapDir;

    @Before
    public void setUp() throws Exception {
        Directory dir = new MockDirectory();
        dir.add("/one", null, CreateMode.PERSISTENT);
        dir.add("/one/two", null, CreateMode.PERSISTENT);
        mapDir = dir.getSubDirectory("/one/two");
    }

    @Test
    public void testStringMapStartEmpty() throws NoNodeException {
        ReplicatedStringMap strMap = new ReplicatedStringMap(mapDir);
        Map<String, String> expectedMap = new HashMap<String, String>();
        strMap.start();
        Assert.assertEquals(expectedMap, strMap.getMap());
        strMap.stop();
        Assert.assertEquals(expectedMap, strMap.getMap());
    }

    @Test
    public void testStringMapStartWithSomeEntries() throws NoNodeException,
            NodeExistsException, NoChildrenForEphemeralsException {
        ReplicatedStringMap strMap = new ReplicatedStringMap(mapDir);
        Map<String, String> expectedMap = new HashMap<String, String>();
        expectedMap.put("one", "100");
        mapDir.add("/one,100,", null, CreateMode.PERSISTENT_SEQUENTIAL);
        expectedMap.put("two", "200");
        mapDir.add("/two,200,", null, CreateMode.PERSISTENT_SEQUENTIAL);
        expectedMap.put("three", "300");
        mapDir.add("/three,300,", null, CreateMode.PERSISTENT_SEQUENTIAL);
        strMap.start();
        Assert.assertEquals(expectedMap, strMap.getMap());
        strMap.stop();
        expectedMap.clear();
        Assert.assertEquals(expectedMap, strMap.getMap());
    }

    @Test
    public void testStringMapAddUpdateGetRemove() throws 
            NoNodeException, NodeExistsException,
            NoChildrenForEphemeralsException {
        ReplicatedStringMap strMap = new ReplicatedStringMap(mapDir);
        Map<String, String> expectedMap = new HashMap<String, String>();
        expectedMap.put("one", "100");
        mapDir.add("/one,100,", null, CreateMode.PERSISTENT_SEQUENTIAL);
        strMap.start();
        Assert.assertEquals(expectedMap, strMap.getMap());
        expectedMap.put("two", "200");
        strMap.put("two", "200");
        Assert.assertEquals(expectedMap, strMap.getMap());
        expectedMap.put("three", "300");
        strMap.put("three", "300");
        Assert.assertEquals(expectedMap, strMap.getMap());
        expectedMap.put("two", "changed200");
        strMap.put("two", "changed200");
        expectedMap.put("three", "changed300");
        strMap.put("three", "changed300");
        Assert.assertEquals(expectedMap, strMap.getMap());
        Assert.assertEquals("100", strMap.get("one"));
        Assert.assertEquals("changed200", strMap.get("two"));
        Assert.assertEquals("changed300", strMap.get("three"));
        expectedMap.remove("one");
        strMap.remove("one");
        Assert.assertEquals(expectedMap, strMap.getMap());
        expectedMap.remove("two");
        strMap.remove("two");
        Assert.assertEquals(expectedMap, strMap.getMap());
        strMap.stop();
        expectedMap.clear();
        Assert.assertEquals(expectedMap, strMap.getMap());
    }

    @Test
    public void testStringMapExternalChanges() throws 
            NoNodeException, NodeExistsException,
            NoChildrenForEphemeralsException {
        ReplicatedStringMap strMap = new ReplicatedStringMap(mapDir);
        Map<String, String> expectedMap = new HashMap<String, String>();
        expectedMap.put("one", "100");
        String path1 = mapDir.add("/one,100,", null,
                CreateMode.PERSISTENT_SEQUENTIAL);
        expectedMap.put("two", "200");
        String path2 = mapDir.add("/two,200,", null,
                CreateMode.PERSISTENT_SEQUENTIAL);
        expectedMap.put("three", "300");
        String path3 = mapDir.add("/three,300,", null,
                CreateMode.PERSISTENT_SEQUENTIAL);
        strMap.start();
        Assert.assertEquals(expectedMap, strMap.getMap());
        expectedMap.put("one", "100changed1");
        strMap.put("one", "100changed1");
        // The 'external' old owner of that key deletes its obsolete value.
        mapDir.delete(path1);
        expectedMap.put("two", "200changed2");
        strMap.put("two", "200changed2");
        Assert.assertEquals(expectedMap, strMap.getMap());
        // The 'external' old owner of that key deletes its obsolete value.
        mapDir.delete(path2);
        Assert.assertEquals(expectedMap, strMap.getMap());
        expectedMap.put("ten", "10");
        strMap.put("ten", "10");
        expectedMap.put("eleven", "11");
        strMap.put("eleven", "11");
        Assert.assertEquals(expectedMap, strMap.getMap());
        // The external node fights back.
        expectedMap.put("one", "100changed3");
        path1 = mapDir.add("/one,100changed3,", null, 
                CreateMode.PERSISTENT_SEQUENTIAL);
        Assert.assertEquals(expectedMap, strMap.getMap());
        expectedMap.put("two", "200changed4");
        path2 = mapDir.add("/two,200changed4,", null,
                CreateMode.PERSISTENT_SEQUENTIAL);
        expectedMap.put("four", "400");
        String path4 = mapDir.add("/four,400,", null,
                CreateMode.PERSISTENT_SEQUENTIAL);
        Assert.assertEquals(expectedMap, strMap.getMap());
        expectedMap.remove("one");
        mapDir.delete(path1);
        expectedMap.remove("two");
        mapDir.delete(path2);
        Assert.assertEquals(expectedMap, strMap.getMap());
        expectedMap.remove("three");
        mapDir.delete(path3);
        Assert.assertEquals(expectedMap, strMap.getMap());
        expectedMap.remove("four");
        mapDir.delete(path4);
        Assert.assertEquals(expectedMap, strMap.getMap());
        strMap.stop();
        expectedMap.clear();
        Assert.assertEquals(expectedMap, strMap.getMap());
    }
}
