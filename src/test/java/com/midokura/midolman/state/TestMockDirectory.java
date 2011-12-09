package com.midokura.midolman.state;

import java.util.HashSet;
import java.util.Set;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NoChildrenForEphemeralsException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.KeeperException.NotEmptyException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;


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
}
