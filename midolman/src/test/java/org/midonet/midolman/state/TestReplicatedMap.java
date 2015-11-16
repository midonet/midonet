/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.midolman.state;

import java.util.HashMap;
import java.util.Map;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.junit.Assert;

import org.junit.Before;
import org.junit.Test;

import org.midonet.cluster.backend.Directory;


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

        Location() {}

        Location(int a, int b, boolean free) {
            this.a = a;
            this.b = b;
            this.free = free;
        }

        @Override
        public boolean equals(Object object){
            if (!(object instanceof Location))
                return false;
            Location other = (Location)object;
            return a == other.a && b == other.b && free == other.free;
        }
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
            builder.append(value.a).append(";");
            builder.append(value.b).append(";");
            builder.append(value.free);
            return builder.toString();
        }

        @Override
        protected Location decodeValue(String str) {
            String[] parts = str.split(";");
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
    public void testStringMapStartWithSomeEntries()
            throws KeeperException, InterruptedException {
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
    public void testStringMapContainsValue()
            throws KeeperException, InterruptedException {
        ReplicatedStringMap strMap = new ReplicatedStringMap(mapDir);
        mapDir.add("/one,100,", null, CreateMode.PERSISTENT_SEQUENTIAL);
        strMap.start();
        Assert.assertTrue(strMap.containsValue("100"));
        Assert.assertFalse(strMap.containsValue("200"));
    }

    @Test
    public void testStringMapAddUpdateGetRemove() throws
            KeeperException, InterruptedException {
        ReplicatedStringMap strMap = new ReplicatedStringMap(mapDir);
        Map<String, String> expectedMap = new HashMap<String, String>();
        expectedMap.put("one", "100");
        String firstEntry =
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
        // The entry with key "one" must be removed via the Directory because
        // that's the way it was added - it doesn't belong to the ReplicatedMap.
        mapDir.delete(firstEntry);
        Assert.assertEquals(expectedMap, strMap.getMap());
        expectedMap.remove("two");
        strMap.removeIfOwner("two");
        Assert.assertEquals(expectedMap, strMap.getMap());
        strMap.stop();
        expectedMap.clear();
        Assert.assertEquals(expectedMap, strMap.getMap());
    }

    @Test
    public void testStringMapExternalChanges() throws
            KeeperException, InterruptedException {
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
        // The external node writes key 'one' again.
        expectedMap.put("one", "100changed3");
        path1 = mapDir.add("/one,100changed3,", null,
                CreateMode.PERSISTENT_SEQUENTIAL);
        Assert.assertEquals(expectedMap, strMap.getMap());
        // External node removes key 'one'. Our old value shouldn't come back.
        expectedMap.remove("one");
        mapDir.delete(path1);
        Assert.assertEquals(expectedMap, strMap.getMap());
        expectedMap.put("two", "200changed4");
        path2 = mapDir.add("/two,200changed4,", null,
                CreateMode.PERSISTENT_SEQUENTIAL);
        expectedMap.put("four", "400");
        String path4 = mapDir.add("/four,400,", null,
                CreateMode.PERSISTENT_SEQUENTIAL);
        Assert.assertEquals(expectedMap, strMap.getMap());
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

    private class MyWatcher implements ReplicatedMap.Watcher<String, String> {
        Map<String, String> map1 = new HashMap<String, String>();
        Map<String, String> map2 = new HashMap<String, String>();

        @Override
        public void processChange(String key, String oldValue,
                String newValue) {
            if (null == newValue)
                map1.remove(key);
            else
                map1.put(key, newValue);
            if (null == oldValue)
                map2.remove(key);
            else
                map2.put(key, oldValue);
        }
    }

    @Test
    public void testChangeWatchers() throws KeeperException, InterruptedException {
        Map<String,String> oldValuesMap = new HashMap<String, String>();
        Map<String, String> expectedMap = new HashMap<String, String>();
        expectedMap.put("one", "100");
        String path1 = mapDir.add("/one,100,", null,
                CreateMode.PERSISTENT_SEQUENTIAL);
        expectedMap.put("two", "200");
        String path2 = mapDir.add("/two,200,", null,
                CreateMode.PERSISTENT_SEQUENTIAL);
        expectedMap.put("three", "300");
        mapDir.add("/three,300,", null,
                CreateMode.PERSISTENT_SEQUENTIAL);
        ReplicatedStringMap strMap = new ReplicatedStringMap(mapDir);
        MyWatcher watch1 = new MyWatcher();
        MyWatcher watch2 = new MyWatcher();
        strMap.addWatcher(watch1);
        strMap.addWatcher(watch2);
        strMap.start();
        Assert.assertEquals(expectedMap, watch1.map1);
        Assert.assertEquals(expectedMap, watch2.map1);
        Assert.assertEquals(oldValuesMap, watch1.map2);
        Assert.assertEquals(oldValuesMap, watch2.map2);
        oldValuesMap.put("one", expectedMap.remove("one"));
        mapDir.delete(path1);
        Assert.assertEquals(expectedMap, watch1.map1);
        Assert.assertEquals(expectedMap, watch2.map1);
        Assert.assertEquals(oldValuesMap, watch1.map2);
        Assert.assertEquals(oldValuesMap, watch2.map2);
        expectedMap.put("ten", "Blah");
        strMap.put("ten", "Blah");
        Assert.assertEquals(expectedMap, watch1.map1);
        Assert.assertEquals(expectedMap, watch2.map1);
        Assert.assertEquals(oldValuesMap, watch1.map2);
        Assert.assertEquals(oldValuesMap, watch2.map2);
        oldValuesMap.put("ten", expectedMap.put("ten", "Blah1"));
        String tenPath = mapDir.add(
                "/ten,Blah1,", null, CreateMode.PERSISTENT_SEQUENTIAL);
        Assert.assertEquals(expectedMap, watch1.map1);
        Assert.assertEquals(expectedMap, watch2.map1);
        Assert.assertEquals(oldValuesMap, watch1.map2);
        Assert.assertEquals(oldValuesMap, watch2.map2);
        oldValuesMap.put("two", expectedMap.put("two", "201"));
        String path = mapDir.add("/two,201,", null,
                CreateMode.PERSISTENT_SEQUENTIAL);
        mapDir.delete(path2);
        path2 = path;
        Assert.assertEquals(expectedMap, watch1.map1);
        Assert.assertEquals(expectedMap, watch2.map1);
        Assert.assertEquals(oldValuesMap, watch1.map2);
        Assert.assertEquals(oldValuesMap, watch2.map2);
        oldValuesMap.put("ten", expectedMap.remove("ten"));
        // The entry with key "ten" must be removed via the Directory because
        // it's not owned by the ReplicatedMap.
        mapDir.delete(tenPath);
        oldValuesMap.put("two", expectedMap.remove("two"));
        mapDir.delete(path2);
        Assert.assertEquals(expectedMap, watch1.map1);
        Assert.assertEquals(expectedMap, watch2.map1);
        Assert.assertEquals(oldValuesMap, watch1.map2);
        Assert.assertEquals(oldValuesMap, watch2.map2);
        // Now unregister the watchers. Verify they no longer get notified.
        strMap.removeWatcher(watch1);
        strMap.removeWatcher(watch2);
        strMap.put("eleven", "Foo");
        Assert.assertEquals(expectedMap, watch1.map1);
        Assert.assertEquals(expectedMap, watch2.map1);
        Assert.assertEquals(oldValuesMap, watch1.map2);
        Assert.assertEquals(oldValuesMap, watch2.map2);
    }

    @Test
    public void testStringToLocationMap() throws KeeperException, InterruptedException {
        ReplicatedStringToLocationMap strMap = new
                ReplicatedStringToLocationMap(mapDir);
        Map<String, Location> expectedMap = new HashMap<String, Location>();
        Location loc1 = new Location(1, 2, false);
        expectedMap.put("one", loc1);
        String path1 = mapDir.add("/one,"+strMap.encodeValue(loc1)+",", null,
                CreateMode.PERSISTENT_SEQUENTIAL);
        strMap.start();
        Assert.assertEquals(expectedMap, strMap.getMap());
        Location loc2 = new Location(10, 20, false);
        expectedMap.put("two", loc2);
        strMap.put("two", loc2);
        Location loc3 = new Location(5, 8, true);
        expectedMap.put("three", loc3);
        strMap.put("three", loc3);
        Assert.assertEquals(expectedMap, strMap.getMap());
        expectedMap.remove("three");
        strMap.removeIfOwner("three");
        Assert.assertEquals(expectedMap, strMap.getMap());
        expectedMap.put("one", loc2);
        strMap.put("one", loc2);
        mapDir.delete(path1);
        Assert.assertEquals(expectedMap, strMap.getMap());
        expectedMap.put("one", loc1);
        path1 = mapDir.add("/one,"+strMap.encodeValue(loc1)+",", null,
                CreateMode.PERSISTENT_SEQUENTIAL);
        Assert.assertEquals(expectedMap, strMap.getMap());
        expectedMap.remove("one");
        mapDir.delete(path1);
        Assert.assertEquals(expectedMap, strMap.getMap());
        strMap.stop();
        expectedMap.clear();
        Assert.assertEquals(expectedMap, strMap.getMap());
    }
}
