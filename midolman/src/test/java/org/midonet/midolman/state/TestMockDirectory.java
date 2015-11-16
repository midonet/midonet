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

import java.util.HashSet;
import java.util.Set;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NoChildrenForEphemeralsException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.KeeperException.NotEmptyException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import org.midonet.cluster.backend.Directory;


public class TestMockDirectory {

    private MockDirectory dir;

    @Before
    public void setUp() {
        dir = new MockDirectory();
    }

    @Test
    public void testAddUpdateGet() throws NoNodeException, NodeExistsException,
            NoChildrenForEphemeralsException {
        String path = "/one";
        byte[] bytes = "pino".getBytes();
        String actualPath = dir.add(path, bytes, CreateMode.PERSISTENT);
        Assert.assertEquals(path, actualPath);
        Assert.assertArrayEquals(bytes, dir.get(path, null));
        bytes = "adam".getBytes();
        dir.update(path, bytes);
        Assert.assertArrayEquals(bytes, dir.get(path, null));
    }

    @Test
    public void testAddSequential() throws NoNodeException, NodeExistsException,
            NoChildrenForEphemeralsException {
        dir.add("/a", "a".getBytes(), CreateMode.PERSISTENT);
        dir.add("/a/b", "ab".getBytes(), CreateMode.PERSISTENT);
        int i = 0;
        while(i < 5) {
            String path = "/a/b/mySequentialNode";
            String actualPath = dir.add(path, null,
                    CreateMode.PERSISTENT_SEQUENTIAL);
            Assert.assertEquals(String.format("%s%010d", path, new Integer(i)),
                    actualPath);
            i++;
        }
    }

    @Test
    public void testAddGetChildren() throws NoNodeException,
            NodeExistsException, NoChildrenForEphemeralsException {
        dir.add("/a", "a".getBytes(), CreateMode.PERSISTENT);
        dir.add("/a/b", "ab".getBytes(), CreateMode.PERSISTENT);
        dir.add("/a/b/c", "abc".getBytes(), CreateMode.PERSISTENT);
        dir.add("/a/b/d", "abd".getBytes(), CreateMode.PERSISTENT);
        dir.add("/a/b/e", "abe".getBytes(), CreateMode.PERSISTENT);
        Set<String> expectedChildren = new HashSet<String>();
        expectedChildren.add("c");
        expectedChildren.add("d");
        expectedChildren.add("e");
        Assert.assertEquals(expectedChildren,
                            dir.getChildren("/a/b", null));
    }

    @Test
    public void testAddHasDelete() throws NoNodeException, NodeExistsException,
            NoChildrenForEphemeralsException, NotEmptyException {
        String path = "/one";
        byte[] bytes = "pino".getBytes();
        dir.add(path, bytes, CreateMode.PERSISTENT);
        Assert.assertArrayEquals(bytes, dir.get(path, null));
        Assert.assertTrue(dir.has(path));
        Assert.assertFalse(dir.has("/anotherpath"));
        Assert.assertFalse(dir.has("/some/other/path"));
        dir.delete(path);
        Assert.assertFalse(dir.has(path));
    }

    private class MyRunnable implements Runnable {
        int ran = 0;

        @Override
        public void run() {
            ran++;
        }
    }

    @Test
    public void testGetWatcher() throws NoNodeException, NodeExistsException,
            NoChildrenForEphemeralsException, NotEmptyException {
        String path = "/one";
        byte[] bytes = "two".getBytes();
        dir.add(path, bytes, CreateMode.PERSISTENT);
        MyRunnable runner1 = new MyRunnable();
        MyRunnable runner2 = new MyRunnable();
        Assert.assertArrayEquals(bytes, dir.get(path, runner1));
        Assert.assertArrayEquals(bytes, dir.get(path, runner2));
        Assert.assertEquals(0, runner1.ran);
        Assert.assertEquals(0, runner2.ran);
        bytes = "three".getBytes();
        dir.update(path, bytes);
        Assert.assertEquals(1, runner1.ran);
        Assert.assertEquals(1, runner2.ran);
        // Only re-register the first watcher.
        Assert.assertArrayEquals(bytes, dir.get(path, runner1));
        bytes = "four".getBytes();
        dir.update(path, bytes);
        Assert.assertEquals(2, runner1.ran);
        Assert.assertEquals(1, runner2.ran);
        // Re-register the first watcher.
        Assert.assertArrayEquals(bytes, dir.get(path, runner1));
        // Deletion should also notify the watcher.
        dir.delete(path);
        Assert.assertEquals(3, runner1.ran);
        Assert.assertEquals(1, runner2.ran);
    }

    @Test
    public void testGetChildrenWatcher() throws NoNodeException,
            NodeExistsException, NoChildrenForEphemeralsException, NotEmptyException {
        dir.add("/a", "a".getBytes(), CreateMode.PERSISTENT);
        dir.add("/a/b", "ab".getBytes(), CreateMode.PERSISTENT);
        dir.add("/a/b/c", "abc".getBytes(), CreateMode.PERSISTENT);
        dir.add("/a/b/d", "abd".getBytes(), CreateMode.PERSISTENT);
        dir.add("/a/b/e", "abe".getBytes(), CreateMode.PERSISTENT);
        MyRunnable runner1 = new MyRunnable();
        MyRunnable runner2 = new MyRunnable();
        Set<String> expectedChildren = new HashSet<String>();
        expectedChildren.add("c");
        expectedChildren.add("d");
        expectedChildren.add("e");
        Assert.assertEquals(expectedChildren,
                dir.getChildren("/a/b", runner1));
        Assert.assertEquals(expectedChildren,
                dir.getChildren("/a/b", runner2));
        Assert.assertEquals(0, runner1.ran);
        Assert.assertEquals(0, runner2.ran);
        dir.add("/a/b/f", "abf".getBytes(), CreateMode.PERSISTENT);
        Assert.assertEquals(1, runner1.ran);
        Assert.assertEquals(1, runner2.ran);
        // Check the set membership and re-register the first watcher.
        expectedChildren.add("f");
        Assert.assertEquals(expectedChildren,
                dir.getChildren("/a/b", runner1));
        dir.delete("/a/b/d");
        Assert.assertEquals(2, runner1.ran);
        Assert.assertEquals(1, runner2.ran);
    }

    private class MyWatcher implements Watcher, Runnable {
        int creates = 0;
        int deletes = 0;
        int dataChanges = 0;
        int childUpdates = 0;

        @Override
        public void process(WatchedEvent event) {
            if (Event.EventType.NodeCreated == event.getType()) {
                creates++;
            } else if (Event.EventType.NodeDeleted == event.getType()) {
                deletes++;
            } else if (Event.EventType.NodeDataChanged == event.getType()) {
                dataChanges++;
            } else if (Event.EventType.NodeChildrenChanged == event.getType()) {
                childUpdates++;
            }
        }

        @Override
        public void run() {
            dataChanges++;
        }

        public int total() {
            return creates + deletes + dataChanges + childUpdates;
        }
    }

    @Test
    public void testExistsWatcher() throws NoNodeException, NodeExistsException,
                                           NoChildrenForEphemeralsException,
                                           NotEmptyException {
        MyWatcher watcher1 = new MyWatcher();
        MyWatcher watcher2 = new MyWatcher();
        Assert.assertEquals(dir.exists("/a", (Watcher)watcher1), false);
        Assert.assertEquals(dir.exists("/a", (Watcher)watcher2), false);
        Assert.assertEquals(watcher1.total(), 0);
        Assert.assertEquals(watcher2.total(), 0);
        dir.add("/a", "one".getBytes(), CreateMode.PERSISTENT);
        Assert.assertEquals(watcher1.creates, 1);
        Assert.assertEquals(watcher2.creates, 1);
        Assert.assertEquals(watcher1.total(), 1);
        Assert.assertEquals(watcher2.total(), 1);
        Assert.assertArrayEquals("one".getBytes(), dir.get("/a", watcher1));
        Assert.assertArrayEquals("one".getBytes(), dir.get("/a", watcher2));
        dir.update("/a", "two".getBytes());
        Assert.assertEquals(watcher1.dataChanges, 1);
        Assert.assertEquals(watcher2.dataChanges, 1);
        Assert.assertEquals(watcher1.total(), 2);
        Assert.assertEquals(watcher2.total(), 2);
        Assert.assertEquals(dir.exists("/a", (Watcher)watcher1), true);
        dir.delete("/a");
        Assert.assertEquals(watcher1.deletes, 1);
        Assert.assertEquals(watcher2.deletes, 0);
        Assert.assertEquals(watcher1.total(), 3);
        Assert.assertEquals(watcher2.total(), 2);

        MyWatcher watcher3 = new MyWatcher();
        Assert.assertEquals(dir.exists("/a/b/c/d", (Watcher)watcher3), false);
        dir.add("/a", "a".getBytes(), CreateMode.PERSISTENT);
        Assert.assertEquals(watcher3.total(), 0);
        dir.add("/a/b", "ab".getBytes(), CreateMode.PERSISTENT);
        Assert.assertEquals(watcher3.total(), 0);
        dir.add("/a/b/c", "abc".getBytes(), CreateMode.PERSISTENT);
        Assert.assertEquals(watcher3.total(), 0);
        dir.add("/a/b/c/d", "abcd0".getBytes(), CreateMode.PERSISTENT);
        Assert.assertEquals(watcher3.creates, 1);
        Assert.assertEquals(watcher3.total(), 1);
        Assert.assertEquals(dir.exists("/a/b/c/d", (Watcher)watcher3), true);
        dir.update("/a/b/c/d", "abcd1".getBytes());
        Assert.assertEquals(watcher3.dataChanges, 1);
        Assert.assertEquals(watcher3.total(), 2);
        dir.update("/a/b/c/d", "abcd2".getBytes());
        Assert.assertEquals(watcher3.dataChanges, 1);
        Assert.assertEquals(watcher3.total(), 2);
        Assert.assertEquals(dir.exists("/a/b/c/d", (Watcher)watcher3), true);
        dir.delete("/a/b/c/d");
        Assert.assertEquals(watcher3.deletes, 1);
        Assert.assertEquals(watcher3.total(), 3);
    }

    @Test
    public void testSubDirectory() throws
        KeeperException, InterruptedException {
        dir.add("/a", "a".getBytes(), CreateMode.PERSISTENT);
        dir.add("/a/b", "ab".getBytes(), CreateMode.PERSISTENT);
        dir.add("/a/b/c", "abc".getBytes(), CreateMode.PERSISTENT);
        dir.add("/a/b/c/d", "abcd".getBytes(), CreateMode.PERSISTENT);
        dir.add("/a/b/c/e", "abce".getBytes(), CreateMode.PERSISTENT);
        dir.add("/a/b/c/f", "abcf".getBytes(), CreateMode.PERSISTENT);
        dir.add("/a/b/c/d/h", "abcdh".getBytes(), CreateMode.PERSISTENT);
        Directory subdir = dir.getSubDirectory("/a/b/c");
        Assert.assertArrayEquals("abcd".getBytes(), subdir.get("/d", null));
        Assert.assertArrayEquals("abcdh".getBytes(), subdir.get("/d/h", null));
        subdir.delete("/d/h");
        Assert.assertFalse(subdir.has("/d/h"));
        Assert.assertFalse(dir.has("/a/b/c/d/h"));
        Set<String> expectedChildren = new HashSet<String>();
        expectedChildren.add("d");
        expectedChildren.add("e");
        expectedChildren.add("f");
        Assert.assertEquals(expectedChildren,
                            subdir.getChildren("/", null));
        Assert.assertEquals(expectedChildren,
                            dir.getChildren("/a/b/c", null));
        subdir.add("/x", "x".getBytes(), CreateMode.PERSISTENT);
        subdir.add("/x/y", "xy".getBytes(), CreateMode.PERSISTENT);
        String actualPath = subdir.add("/x/y/z", "xyz".getBytes(),
                CreateMode.PERSISTENT);
        Assert.assertEquals("/x/y/z", actualPath);
        Assert.assertArrayEquals("xyz".getBytes(),
                subdir.get(actualPath, null));
    }

    @Test(expected=NoNodeException.class)
    public void testNoNode() throws
        NodeExistsException, NoChildrenForEphemeralsException, NoNodeException {
        dir.add("/one/two", "pino".getBytes(), CreateMode.PERSISTENT);
    }

    @Test(expected=NodeExistsException.class)
    public void testNodeExists() throws
        NodeExistsException, NoChildrenForEphemeralsException, NoNodeException {
        String path = "/somenode";
        try {
            dir.add(path, "pino".getBytes(), CreateMode.PERSISTENT);
        } catch (NodeExistsException e) {
            Assert.fail();
        }
        dir.add(path, "pino".getBytes(), CreateMode.PERSISTENT);
    }

    @Test(expected=NoChildrenForEphemeralsException.class)
    public void testNoChildrenForEphemerals() throws
        NoNodeException, NodeExistsException, NoChildrenForEphemeralsException {
        dir.add("/one", "pino".getBytes(), CreateMode.EPHEMERAL);
        dir.add("/one/two", "jacob".getBytes(), CreateMode.PERSISTENT);
    }

    @Test
    public void testGetNodeWithNullData() {
        String path = "/nodeWithNullData";
        try {
            dir.add(path, null, CreateMode.PERSISTENT);
            Assert.assertNull(dir.get(path, null));
        } catch (Exception e) {
            e.printStackTrace();
            Assert.fail();
        }
    }

    private class MyTypedWatcher implements Directory.TypedWatcher {
        public int deletes = 0;
        public int creates = 0;
        public int childUpdates = 0;
        public int dataChanges = 0;
        public int noChanges = 0;
        public int runs = 0;

        @Override
        public void pathDeleted(String path) {
            deletes++;
        }

        @Override
        public void pathCreated(String path) {
            creates++;
        }

        @Override
        public void pathChildrenUpdated(String path) {
            childUpdates++;
        }

        @Override
        public void pathDataChanged(String path) {
            dataChanges++;
        }

        @Override
        public void connectionStateChanged(Watcher.Event.KeeperState state) {
            noChanges++;
        }

        @Override
        public void run() {
            runs++;
        }
    }

    @Test
    public void testTypedWatchers() throws NoNodeException, NodeExistsException,
                                           NoChildrenForEphemeralsException,
                                           NotEmptyException {
        dir.add("/a", null, CreateMode.PERSISTENT);
        dir.add("/a/b", null, CreateMode.PERSISTENT);
        MyTypedWatcher aWatcher = new MyTypedWatcher();

        dir.get("/a", aWatcher);
        dir.add("/a/c", null, CreateMode.PERSISTENT);
        Assert.assertEquals(1, aWatcher.childUpdates);

        // install watcher
        dir.get("/a/c", aWatcher);
        dir.get("/a", aWatcher);
        dir.delete("/a/c");
        Assert.assertEquals(1, aWatcher.deletes);
        Assert.assertEquals(2, aWatcher.childUpdates);

        // install watcher again
        dir.get("/a", aWatcher);
        dir.update("/a", "a".getBytes());
        Assert.assertEquals(1, aWatcher.dataChanges);
    }
}
