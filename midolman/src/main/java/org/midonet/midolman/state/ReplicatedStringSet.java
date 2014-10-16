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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReplicatedStringSet {

    private final static Logger log = LoggerFactory.getLogger(ReplicatedStringSet.class);

    public interface Watcher {
        void process(Collection<String> addedStrings,
                Collection<String> removedStrings);
    }

    private class DirectoryWatcher implements Runnable {
        public void run() {
            if (!running) {
                return;
            }
            Set<String> oldStrings = strings;
            try {
                strings = new HashSet<String>(dir.getChildren("/", this));
            } catch (KeeperException e) {
                log.error("DirectoryWatcher.run", e);
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                log.error("DirectoryWatcher.run", e);
                Thread.currentThread().interrupt();
            }
            // Compute the newly added strings
            Set<String> addedStrings = new HashSet<String>(strings);
            addedStrings.removeAll(oldStrings);
            // Compute the newly deleted strings
            oldStrings.removeAll(strings);
            if (!addedStrings.isEmpty() || !oldStrings.isEmpty()) {
                notifyWatchers(addedStrings, oldStrings);
            }
        }
    }

    private Directory dir;
    private boolean running;
    private Set<String> strings;
    private Set<Watcher> changeWatchers;
    private DirectoryWatcher myWatcher;

    public ReplicatedStringSet(Directory d) {
        super();
        dir = d;
        running = false;
        strings = new HashSet<String>();
        changeWatchers = new HashSet<Watcher>();
        myWatcher = new DirectoryWatcher();
    }

    public void addWatcher(Watcher watcher) {
        changeWatchers.add(watcher);
    }

    public void removeWatcher(Watcher watcher) {
        changeWatchers.remove(watcher);
    }

    public void start() {
        if (!running) {
            running = true;
            myWatcher.run();
        }
    }

    public void stop() {
        running = false;
        strings.clear();
    }

    public void add(Collection<String> addStrings) throws KeeperException,
            InterruptedException {
        // Just modify the ZK state. Internal structures will be updated
        // when our watcher is called.
        for (String str : addStrings)
            add(str);
    }

    public void remove(Collection<String> delStrings) throws KeeperException,
            InterruptedException {
        // Just modify the ZK state. Internal structures will be updated
        // when our watcher is called.
        for (String str : delStrings)
            remove(str);
    }

    public void add(String str) throws KeeperException, InterruptedException {
        // Just modify the ZK state. Internal structures will be updated
        // when our watcher is called.
        dir.add("/" + str, null, CreateMode.EPHEMERAL);
    }

    public void remove(String str) throws KeeperException, InterruptedException {
        // Just modify the ZK state. Internal structures will be updated
        // when our watcher is called.
        dir.delete("/" + str);
    }

    public Set<String> getStrings() {
        return Collections.unmodifiableSet(strings);
    }

    protected void notifyWatchers(Collection<String> addedStrings,
            Collection<String> removedStrings) {
        for (Watcher watcher : changeWatchers) {
            watcher.process(addedStrings, removedStrings);
        }
    }
}
