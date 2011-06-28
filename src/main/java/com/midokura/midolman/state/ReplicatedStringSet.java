package com.midokura.midolman.state;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.HashSet;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.NoChildrenForEphemeralsException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;


public class ReplicatedStringSet {

    public interface ChangeWatcher {
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
            } 
            catch(NoNodeException e) {
                throw new RuntimeException(e);
            }
            // Compute the newly added strings
            Set<String> addedStrings = new HashSet<String>(strings);
            addedStrings.removeAll(oldStrings);
            // Compute the newly deleted strings
            oldStrings.removeAll(strings);
            if (addedStrings.size() > 0 || oldStrings.size() > 0) {
                notifyWatchers(addedStrings, oldStrings);
            }
        }
    }

    private Directory dir;
    private boolean running;
    private Set<String> strings;
    private Set<ChangeWatcher> changeWatchers;
    private DirectoryWatcher myWatcher;

    public ReplicatedStringSet(Directory d) {
        super();
        dir = d;
        running = false;
        strings = new HashSet<String>();
        changeWatchers = new HashSet<ChangeWatcher>();
        myWatcher = new DirectoryWatcher();
    }

    public void addWatcher(ChangeWatcher watcher) {
        changeWatchers.add(watcher);
    }

    public void removeWatcher(ChangeWatcher watcher) {
        changeWatchers.remove(watcher);
    }
    
    public void start() throws NoNodeException {
        if (!running) {
            running = true;
            myWatcher.run();
        }
    }

    public void stop() {
        running = false;
        strings.clear();
    }

    public void add(Collection<String> addStrings) throws NoNodeException,
            NodeExistsException, NoChildrenForEphemeralsException {
        // Just modify the ZK state. Internal structures will be updated
        // when our watcher is called.
        for (String str : addStrings)
            add(str);
    }

    public void remove(Collection<String> delStrings) throws NoNodeException {
        // Just modify the ZK state. Internal structures will be updated
        // when our watcher is called.
        for (String str : delStrings)
            remove(str);
    }

    public void add(String str) throws NoNodeException, NodeExistsException,
            NoChildrenForEphemeralsException {
        // Just modify the ZK state. Internal structures will be updated
        // when our watcher is called.
        dir.add("/" + str, null, CreateMode.EPHEMERAL);
    }

    public void remove(String str) throws NoNodeException {
        // Just modify the ZK state. Internal structures will be updated
        // when our watcher is called.
        dir.delete("/" + str);
    }

    public Set<String> getStrings() {
        return Collections.unmodifiableSet(strings);
    }

    protected void notifyWatchers(Collection<String> addedStrings,
            Collection<String> removedStrings)
    {
        for (ChangeWatcher watcher : changeWatchers) {
            watcher.process(addedStrings, removedStrings);
        }
    }
}
