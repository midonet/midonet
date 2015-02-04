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

import org.midonet.cluster.backend.zookeeper.Directory;
import org.midonet.cluster.backend.zookeeper.MockDirectory;
import org.midonet.cluster.state.ReplicatedSet;
import org.midonet.midolman.layer3.Route;
import org.midonet.cluster.backend.zookeeper.serialization.SerializationException;


public class TestReplicatedSet {

    private class MyReplicatedStringSet extends ReplicatedSet<String> {

        public MyReplicatedStringSet(Directory d, CreateMode createMode) {
            super(d, createMode);
        }

        @Override
        protected String encode(String item) {
            return item;
        }

        @Override
        protected String decode(String str) {
            return str;
        }
        
    }

    private class ReplicatedRouteSet extends ReplicatedSet<Route> {

        public ReplicatedRouteSet(Directory d, CreateMode cMode) {
            super(d, cMode);
        }

        @Override
        protected String encode(Route item) {
            return item.toString();
        }

        @Override
        protected Route decode(String str) {
            return Route.fromString(str);
        }
    }

    Directory stringDir;
    MyReplicatedStringSet stringSet;
    Set<String> testStrings;
    Set<String> emptyStringSet = Collections.emptySet();
    Directory routeDir;
    ReplicatedRouteSet routeSet;
    Set<Route> testRoutes;
    Set<Route> emptyRouteSet = Collections.emptySet();

    @Before
    public void setUp() throws KeeperException, InterruptedException {
        Directory dir = new MockDirectory();
        dir.add("/top", null, CreateMode.PERSISTENT);
        dir.add("/top/strings", null, CreateMode.PERSISTENT);
        dir.add("/top/routes", null, CreateMode.PERSISTENT);
        stringDir = dir.getSubDirectory("/top/strings");
        stringSet = new MyReplicatedStringSet(stringDir, CreateMode.EPHEMERAL);
        testStrings = new HashSet<String>();
        testStrings.add("foo");
        testStrings.add("bar");
        testStrings.add("pie");
        routeDir = dir.getSubDirectory("/top/routes");
        routeSet = new ReplicatedRouteSet(routeDir, CreateMode.EPHEMERAL);
        testRoutes = new HashSet<Route>();
        testRoutes.add(new Route(0, 0, 0, 0, null, null, 0, 0, null, null));
        testRoutes.add(new Route(0x01, 0, 0, 0, null, null, 0, 0, null, null));
        testRoutes.add(new Route(0, 0, 0x01, 0, null, null, 0, 0, null, null));
    }

    @Test
    public void testStartEmpty() {
        stringSet.start();
        Assert.assertEquals(emptyStringSet, stringSet.getStrings());
        stringSet.stop();
        Assert.assertEquals(emptyStringSet, stringSet.getStrings());
    }

    @Test
    public void testAddToDirectoryThenStart() throws KeeperException,
            InterruptedException {
        for (String str : testStrings) {
            stringDir.add("/" + str, null, CreateMode.PERSISTENT);
        }
        stringSet.start();
        Assert.assertEquals(testStrings, stringSet.getStrings());
    }

    @Test
    public void testStartThenAddToDirectory() throws KeeperException,
            InterruptedException {
        stringSet.start();
        for (String str : testStrings) {
            stringDir.add("/" + str, null, CreateMode.PERSISTENT);
        }
        Assert.assertEquals(testStrings, stringSet.getStrings());
    }

    @Test
    public void testStartThenAdd() throws KeeperException, InterruptedException,
            SerializationException {
        stringSet.start();
        for (String str : testStrings) {
            stringSet.add(str);
        }
        Assert.assertEquals(testStrings, stringSet.getStrings());
    }

    @Test
    public void testStartThenAddDelete() throws KeeperException,
            InterruptedException, SerializationException {
        stringSet.start();
        for (String str : testStrings) {
            stringSet.add(str);
        }
        Assert.assertEquals(testStrings, stringSet.getStrings());
        testStrings.remove("foo");
        stringSet.remove("foo");
        Assert.assertEquals(testStrings, stringSet.getStrings());
    }

    @Test
    public void testStartThenAddDeleteToDirectory() throws KeeperException,
            InterruptedException, SerializationException {
        stringSet.start();
        for (String str : testStrings) {
            stringDir.add("/" + str, null, CreateMode.PERSISTENT);
        }
        Assert.assertEquals(testStrings, stringSet.getStrings());
        testStrings.remove("foo");
        stringDir.delete("foo");
        Assert.assertEquals(testStrings, stringSet.getStrings());
    }

    public void testAddStringExists() throws KeeperException,
            InterruptedException, SerializationException {
        // Since the stringSet's CreateMode is EPHEMERAL, it does not throw
        // NodeExists exceptions - it overwrites the existing node to claim
        // ownership.
        stringSet.start();
        stringSet.add("foo");
        stringSet.add("foo");
    }

    private class MyWatcher implements ReplicatedSet.Watcher<String> {
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
            InterruptedException, SerializationException {
        MyWatcher watch1 = new MyWatcher();
        MyWatcher watch2 = new MyWatcher();
        stringSet.addWatcher(watch1);
        stringSet.addWatcher(watch2);
        for (String str : testStrings) {
            stringSet.add(str);
        }
        // No notifications yet because the StringSet hasn't been started.
        Assert.assertEquals(emptyStringSet, watch1.strings);
        Assert.assertEquals(emptyStringSet, watch2.strings);
        stringSet.start();
        Assert.assertEquals(testStrings, watch1.strings);
        Assert.assertEquals(testStrings, watch2.strings);
        stringSet.remove("foo");
        testStrings.remove("foo");
        Assert.assertEquals(testStrings, watch1.strings);
        Assert.assertEquals(testStrings, watch2.strings);
        // Construct a set with just "bar" and "pie"
        stringSet.remove("bar");
        stringSet.remove("pie");
        // Now the watchers' sets should be empty.
        Assert.assertEquals(emptyStringSet, watch1.strings);
        Assert.assertEquals(emptyStringSet, watch2.strings);
        // Unregister the second watch
        stringSet.removeWatcher(watch2);
        testStrings.clear();
        testStrings.add("fox");
        stringSet.add("fox");
        Assert.assertEquals(testStrings, watch1.strings);
        Assert.assertEquals(emptyStringSet, watch2.strings);
    }
}
