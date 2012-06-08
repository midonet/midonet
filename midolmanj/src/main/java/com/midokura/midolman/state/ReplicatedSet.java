/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.state;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class ReplicatedSet<T> {

    private final static Logger log = LoggerFactory.getLogger(ReplicatedSet.class);

    public interface Watcher<T1> {
        void process(Collection<T1> added, Collection<T1> removed);
    }

    private class DirectoryWatcher implements Runnable {
        public void run() {
            if (!running) {
                return;
            }
            Set<String> oldStrings = strings;
            try {
                strings = new HashSet<String>(dir.getChildren("", this));
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
            Set<T> addedItems = new HashSet<T>();
            for (String str : addedStrings) {
                addedItems.add(decode(str));
            }
            // Compute the newly deleted strings
            oldStrings.removeAll(strings);
            Set<T> deletedItems = new HashSet<T>();
            for (String str : oldStrings) {
                deletedItems.add(decode(str));
            }
            if (addedItems.size() > 0 || deletedItems.size() > 0) {
                notifyWatchers(addedItems, deletedItems);
            }
        }
    }

    private Directory dir;
    private boolean running;
    private CreateMode createMode;
    private Set<String> strings;
    private Set<Watcher<T>> changeWatchers;
    private DirectoryWatcher myWatcher;

    public ReplicatedSet(Directory d, CreateMode createMode) {
        super();
        dir = d;
        this.createMode = createMode;
        running = false;
        strings = new HashSet<String>();
        changeWatchers = new HashSet<Watcher<T>>();
        myWatcher = new DirectoryWatcher();
    }

    public void addWatcher(Watcher<T> watcher) {
        changeWatchers.add(watcher);
    }

    public void removeWatcher(Watcher<T> watcher) {
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

    public void add(T item) throws KeeperException {
        // Just modify the ZK state. Internal structures will be updated
        // when our watcher is called.
        String path = "/" + encode(item);
        try {
            dir.add(path, null, createMode);
        } catch (NodeExistsException e) {
            // If the route already exists and we need it to be ephemeral, we
            // delete it and re-create it so that it belongs to this ZK client
            // and will only be removed when this client's session expires.
            if (createMode.equals(CreateMode.EPHEMERAL)) {
                log.warn("Item {} already exists. Delete it and recreate it " +
                        "as an Ephemeral node in order to own it.", item);
                try {
                    // TODO(pino): can it be done in a multi to save a trip?
                    dir.delete(path);
                    dir.add(path, null, createMode);
                } catch (InterruptedException e1) {
                    log.error("Interrupted", e1);
                }
            }
        } catch (InterruptedException e) {
            log.error("Interrupted should never happen.", e);
        }
    }

    public void remove(T item) throws KeeperException {
        // Just modify the ZK state. Internal structures will be updated
        // when our watcher is called.
        try {
            dir.delete("/" + encode(item));
        } catch (InterruptedException e) {
            log.error("Interrupted should never happen.", e);
        }
    }

    public Set<String> getStrings() {
        return Collections.unmodifiableSet(strings);
    }

    protected void notifyWatchers(Collection<T> addedItems,
            Collection<T> removedItems) {
        for (Watcher<T> watcher : changeWatchers) {
            watcher.process(addedItems, removedItems);
        }
    }

    protected abstract String encode(T item);
    protected abstract T decode(String str);
}