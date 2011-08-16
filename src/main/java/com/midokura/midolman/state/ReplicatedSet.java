package com.midokura.midolman.state;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.HashSet;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;

public abstract class ReplicatedSet<T> {

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
                strings = new HashSet<String>(dir.getChildren("/", this));
            } catch (KeeperException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
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
            if (addedStrings.size() > 0 || oldStrings.size() > 0) {
                notifyWatchers(addedItems, deletedItems);
            }
        }
    }

    private Directory dir;
    private boolean running;
    private Set<String> strings;
    private Set<Watcher<T>> changeWatchers;
    private DirectoryWatcher myWatcher;

    public ReplicatedSet(Directory d) {
        super();
        dir = d;
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

    public void add(T item) throws KeeperException, InterruptedException {
        // Just modify the ZK state. Internal structures will be updated
        // when our watcher is called.
        dir.add("/" + encode(item), null, CreateMode.EPHEMERAL);
    }

    public void remove(T item) throws KeeperException, InterruptedException {
        // Just modify the ZK state. Internal structures will be updated
        // when our watcher is called.
        dir.delete("/" + encode(item));
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
