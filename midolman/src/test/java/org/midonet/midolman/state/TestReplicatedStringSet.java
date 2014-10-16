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

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TestReplicatedStringSet {

    ReplicatedStringSet stringSet;
    Directory setDir;
    Set<String> testStrings;
    Set<String> emptySet = Collections.emptySet();

    @Before
    public void setUp() throws KeeperException, InterruptedException {
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
    public void testStartEmpty() {
        stringSet.start();
        Assert.assertEquals(emptySet, stringSet.getStrings());
        stringSet.stop();
        Assert.assertEquals(emptySet, stringSet.getStrings());
    }

    @Test
    public void testAddToDirectoryThenStart() throws KeeperException,
            InterruptedException {
        for (String str : testStrings) {
            setDir.add("/" + str, null, CreateMode.PERSISTENT);
        }
        stringSet.start();
        Assert.assertEquals(testStrings, stringSet.getStrings());
    }

    @Test
    public void testStartThenAddToDirectory() throws KeeperException,
            InterruptedException {
        stringSet.start();
        for (String str : testStrings) {
            setDir.add("/" + str, null, CreateMode.PERSISTENT);
        }
        Assert.assertEquals(testStrings, stringSet.getStrings());
    }

    @Test
    public void testStartThenAdd() throws KeeperException, InterruptedException {
        stringSet.start();
        stringSet.add(testStrings);
        Assert.assertEquals(testStrings, stringSet.getStrings());
    }

    @Test
    public void testStartThenAddDelete() throws KeeperException,
            InterruptedException {
        stringSet.start();
        stringSet.add(testStrings);
        Assert.assertEquals(testStrings, stringSet.getStrings());
        testStrings.remove("foo");
        stringSet.remove("foo");
        Assert.assertEquals(testStrings, stringSet.getStrings());
    }

    @Test
    public void testStartThenAddDeleteToDirectory() throws KeeperException,
            InterruptedException {
        stringSet.start();
        for (String str : testStrings) {
            setDir.add("/" + str, null, CreateMode.PERSISTENT);
        }
        Assert.assertEquals(testStrings, stringSet.getStrings());
        testStrings.remove("foo");
        setDir.delete("foo");
        Assert.assertEquals(testStrings, stringSet.getStrings());
    }

    @Test(expected = KeeperException.NodeExistsException.class)
    public void testAddStringExists() throws KeeperException,
            InterruptedException {
        stringSet.start();
        try {
            stringSet.add("foo");
        } catch (KeeperException.NodeExistsException e) {
            Assert.fail();
        }
        stringSet.add("foo");
    }

    @Test(expected = KeeperException.NoNodeException.class)
    public void testRemoveStringDoesNotExist() throws KeeperException,
            InterruptedException {
        stringSet.start();
        try {
            stringSet.add("foo");
        } catch (KeeperException.NoNodeException e) {
            Assert.fail();
        }
        stringSet.remove("bar");
    }

    private class MyWatcher implements ReplicatedStringSet.Watcher {
        Set<String> strings = new HashSet<String>();

        @Override
        public void process(Collection<String> addedStrings,
                Collection<String> removedStrings) {
            strings.addAll(addedStrings);
            strings.removeAll(removedStrings);
        }
    }

    @Test
    public void testChangeWatchers() throws KeeperException,
            InterruptedException {
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
