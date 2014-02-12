/*
 * Copyright 2011 Midokura KK
 */

package org.midonet.midolman.state;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.serialization.SerializationException;


public abstract class ReplicatedSet<T> {
    private final static Logger log = LoggerFactory.getLogger(ReplicatedSet.class);

    ZkConnectionAwareWatcher connectionWatcher;

    public interface Watcher<T1> {
        void process(Collection<T1> added, Collection<T1> removed);
    }

    public void setConnectionWatcher(ZkConnectionAwareWatcher watcher) {
        connectionWatcher = watcher;
    }

    private void updateItems(Set<String> newStrings)
            throws SerializationException {
        Set<String> oldStrings = strings;
        log.debug("Got set update. Old strings {} New strings {}",
            strings, newStrings);
        // Compute the newly added strings
        Set<String> addedStrings = new HashSet<String>(newStrings);
        addedStrings.removeAll(oldStrings);
        Set<T> addedItems = new HashSet<T>();
        for (String str : addedStrings) {
            addedItems.add(decode(str));
        }
        // Compute the newly deleted strings
        oldStrings.removeAll(newStrings);
        Set<T> deletedItems = new HashSet<T>();
        for (String str : oldStrings) {
            deletedItems.add(decode(str));
        }
        if (!addedItems.isEmpty() || !deletedItems.isEmpty()
                || !firstUpdateSent) {
            notifyWatchers(addedItems, deletedItems);
            firstUpdateSent = true;
        }
        strings = newStrings;
    }

    private class DirectoryWatcher extends Directory.DefaultTypedWatcher {
        @Override
        public void pathChildrenUpdated(String path) {
            if (!running)
                return;
            dir.asyncGetChildren("", new GetItemsCallback(), this);
        }
    }

    private Directory dir;
    private boolean running;
    private boolean firstUpdateSent = false;
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
            myWatcher.pathChildrenUpdated("");
        }
    }

    public void stop() {
        running = false;
        strings.clear();
    }

    public void add(T item) throws SerializationException {
        // Just modify the ZK state. Internal structures will be updated
        // when our watcher is called.
        String path = "/" + encode(item);
        dir.asyncAdd(path, null, createMode, new AddCallback(item));
    }

    public void remove(T item) throws SerializationException {
        // Just modify the ZK state. Internal structures will be updated
        // when our watcher is called.
        dir.asyncDelete("/" + encode(item), new DeleteCallback(item));
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

    class GetItemsCallback implements DirectoryCallback<Set<String>> {
        @Override
        public void onSuccess(Result<Set<String>> data) {
            try {
                updateItems(data.getData());
            } catch (SerializationException e) {
                log.error("Serialization error: ", e);
            }
        }

        @Override
        public void onTimeout() {
            log.error("ReplicatedSet getChildren {} timed out.");
            if (connectionWatcher != null)
                connectionWatcher.handleTimeout(makeRetry());
        }

        @Override
        public void onError(KeeperException e) {
            log.error("ReplicatedSet GetChildren {} failed", e);
            if (connectionWatcher != null)
                connectionWatcher.handleError("ReplicatedSet", makeRetry(), e);
        }

        private Runnable makeRetry() {
            return new Runnable() {
                @Override
                public void run() {
                    dir.asyncGetChildren("", GetItemsCallback.this, myWatcher);
                }
            };
        }
    }

    class DeleteCallback implements DirectoryCallback.Void {
        private T item;

        private DeleteCallback(T item) {
            this.item = item;
        }

        @Override
        public void onSuccess(Result<java.lang.Void> data) {
            log.debug("ReplicatedSet delete {} succeeded", item);
        }

        @Override
        public void onTimeout() {
            log.error("ReplicatedSet delete {} timed out.", item);
        }

        @Override
        public void onError(KeeperException e) {
            log.error("ReplicatedSet Delete {} failed", item, e);
        }
    }

    private class AddCallback implements DirectoryCallback.Add {
        private T item;

        AddCallback(T v) {
            item = v;
        }

        public void onSuccess(Result<String> result) {
            log.info("ReplicatedSet Add {} succeeded", item);
        }

        public void onError(KeeperException ex) {
            if (ex instanceof NodeExistsException) {
                // If the item already exists and we need it to be ephemeral, we
                // delete it and re-create it so that it belongs to this ZK client
                // and will only be removed when this client's session expires.
                if (createMode.equals(CreateMode.EPHEMERAL)) {
                    log.warn("Item {} already exists. Delete it and recreate it " +
                                 "as an Ephemeral node in order to own it.", item);
                    String path = "";
                    try {
                        path = "/" + encode(item);
                    } catch (Exception e) {
                        log.error("Exception when trying to serialize", e);
                        return;
                    }
                    try {
                        // TODO(pino): can it be done in a multi to save a trip?
                        dir.delete(path);
                    } catch (Exception e) {
                        log.error("Exception when trying to delete", e);
                    }
                    dir.asyncAdd(path, null, createMode, new AddCallback(item));
                }
            }
            else
                log.error("ReplicatedSet Add {} failed", item, ex);
        }

        public void onTimeout() {
            log.error("ReplicatedSet Add {} timed out.", item);
        }
    }

    protected abstract String encode(T item) throws SerializationException;
    protected abstract T decode(String str) throws SerializationException;
}
