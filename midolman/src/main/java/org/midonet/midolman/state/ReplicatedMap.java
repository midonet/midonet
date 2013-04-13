/*
 * Copyright 2011 Midokura KK
 */

package org.midonet.midolman.state;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class ReplicatedMap<K, V> {
    private final static Logger log =
         LoggerFactory.getLogger(ReplicatedMap.class);

    protected ZkConnectionAwareWatcher connectionWatcher;

    /*
     * TODO(pino): don't allow deletes to be lost.
     *
     * Problem with current code is that calling remove on a key only removes
     * the value with the highest sequence number. If the owner of the previous
     * sequence number never saw the most recent sequence number than it won't
     * have removed its value and hence the 'delete' will result in that old
     * value coming back.
     *
     * Typical fix is to implement remove by writing a tombstone (e.g. empty
     * string for value) with a higher sequence number.
     *
     * Unresolved issue: who cleans up the tombstones.
     */

    public interface Watcher<K1, V1> {
        void processChange(K1 key, V1 oldValue, V1 newValue);
    }

    public void setConnectionWatcher(ZkConnectionAwareWatcher watcher) {
        connectionWatcher = watcher;
    }

    private static class Notification<K1, V1> {
        K1 key;
        V1 oldValue, newValue;
        Notification(K1 k, V1 v1, V1 v2) {
            key = k;
            oldValue = v1;
            newValue = v2;
        }
    }

    private class DirectoryWatcher implements Runnable {
        public void run() {
            if (!running) {
                return;
            }
            Set<String> curPaths = null;
            try {
                // XXX TODO(pino, rossella): make this asynchronous.
                curPaths = dir.getChildren("/", this);
            } catch (KeeperException e) {
                log.warn("DirectoryWatcher.run {}", e);
                if (connectionWatcher != null) {
                    connectionWatcher.handleError("ReplicatedMap", this, e);
                    return;
                } else {
                    throw new RuntimeException(e);
                }
            } catch (InterruptedException e) {
                log.error("DirectoryWatcher.run {}", e);
                Thread.currentThread().interrupt();
            }
            List<String> cleanupPaths = new LinkedList<String>();
            Set<Notification<K,V>> notifications =
                    new HashSet<Notification<K,V>>();
            Map<K,MapValue> newMap = new HashMap<K,MapValue>();
            // Build newMap from curPaths, using only the highest versioned
            // entry for each key.
            for (String path : curPaths) {
                Path p = decodePath(path);
                MapValue mv = newMap.get(p.key);
                if (mv == null || mv.version < p.version)
                    newMap.put(p.key, new MapValue(p.value, p.version));
            }
            synchronized(ReplicatedMap.this) {
                Set<K> oldKeys = new HashSet<K>(localMap.keySet());
                oldKeys.removeAll(newMap.keySet());
                for (K deletedKey : oldKeys) {
                    MapValue mv = localMap.get(deletedKey);
                    notifications.add(new Notification<K,V>(
                                                deletedKey, mv.value, null));
                }
                for (Map.Entry<K, MapValue> entry : newMap.entrySet()) {
                    K key = entry.getKey();
                    V value = entry.getValue().value;
                    MapValue mv = localMap.get(key);
                    if (mv == null) {
                        notifications.add(new Notification<K,V>(
                                            key, null, value));
                    } else if (mv.version != entry.getValue().version) {
                        // We compare versions because the 'value' members
                        // might not implement .equals accurately.
                        notifications.add(new Notification<K,V>(
                                            key, mv.value, value));
                        // Remember my obsolete paths and clean them up later.
                        if (ownedVersions.contains(mv.version)) {
                            cleanupPaths.add(encodePath(key, mv.value,
                                                        mv.version));
                            ownedVersions.remove(mv.version);
                            log.debug("Cleaning up entry for {} because " +
                                      "{}/{} has been replaced by {}/{}",
                                new Object[]{ key, mv.value, mv.version, value,
                                              entry.getValue().version});
                        }
                    } // else mv == entry:  No notification.
                }
                localMap = newMap;
            }

            for (Notification<K,V> notice : notifications)
                notifyWatchers(notice.key, notice.oldValue, notice.newValue);
            // Now clean up any of my paths that have been obsoleted.
            for (String path : cleanupPaths)
                try {
                    dir.delete(path);
                } catch (KeeperException e) {
                    log.error("DirectoryWatcher.run", e);
                    // XXX(guillermo) connectionWatcher.handleError()?
                    throw new RuntimeException(e);
                } catch (InterruptedException e) {
                    log.error("DirectoryWatcher.run", e);
                    Thread.currentThread().interrupt();
                }
        }
    }

    private Directory dir;
    private boolean running;
    private Map<K, MapValue> localMap;
    private Set<Integer> ownedVersions;
    private Set<Watcher<K, V>> watchers;
    private DirectoryWatcher myWatcher;

    public ReplicatedMap(Directory dir) {
        this.dir = dir;
        this.running = false;
        this.localMap = new HashMap<K, MapValue>();
        this.ownedVersions = new HashSet<Integer>();
        this.watchers = new HashSet<Watcher<K, V>>();
        this.myWatcher = new DirectoryWatcher();
    }

    public void addWatcher(Watcher<K, V> watcher) {
        watchers.add(watcher);
    }

    public void removeWatcher(Watcher<K, V> watcher) {
        watchers.remove(watcher);
    }

    public void start() {
        if (!this.running) {
            this.running = true;
            myWatcher.run();
        }
    }

    public synchronized void stop() {
        this.running = false;
        this.localMap.clear();
    }

    public synchronized V get(K key) {
        MapValue mv = localMap.get(key);
        if (null == mv)
            return null;
        return mv.value;
    }

    public synchronized boolean containsKey(K key) {
        return localMap.containsKey(key);
    }

    public synchronized Map<K, V> getMap() {
        Map<K, V> result = new HashMap<K, V>();
        for (Map.Entry<K, MapValue> entry : localMap.entrySet())
            result.put(entry.getKey(), entry.getValue().value);
        return result;
    }

    public synchronized List<K> getByValue(V value) {
        ArrayList<K> keyList = new ArrayList<K>();
        for (Map.Entry<K, MapValue> entry : localMap.entrySet())
            if (entry.getValue().value.equals(value))
                keyList.add(entry.getKey());
        return keyList;
    }

    private class PutCallback implements DirectoryCallback.Add {
        private K key;
        private V value;

        PutCallback(K k, V v) {
            key = k;
            value = v;
        }

        public void onSuccess(Result<String> result) {
            // Claim the sequence number added by ZooKeeper.
            Path p = decodePath(result.getData());
            synchronized(ReplicatedMap.this) {
                ownedVersions.add(p.version);
            }
        }

        public void onError(KeeperException ex) {
            log.error("ReplicatedMap Put {} => {} failed: {}",
                      new Object[] { key, value, ex });
        }

        public void onTimeout() {
            log.error("ReplicatedMap Put {} => {} timed out.", key, value);
        }
    }

    public void put(final K key, final V value) {
        dir.asyncAdd(encodePath(key, value), null,
                     CreateMode.EPHEMERAL_SEQUENTIAL,
                     new PutCallback(key, value));

        // Our notifies for this change are called from the update
        // notification to the DirectoryWatcher after ZK has accepted it.
    }

    public synchronized boolean isKeyOwner(K key) {
        MapValue mv = localMap.get(key);
        if (null == mv)
            return false;
        return ownedVersions.contains(mv.version);
    }

    // TODO(pino): it might be useful to take the value as another argument.
    // TODO: e.g. to prevent a delayed callback meant to remove an old value
    // TODO: from removing a newer one.
    public V removeIfOwner(K key) throws KeeperException, InterruptedException {
        MapValue mv;
        synchronized(this) {
            mv = localMap.get(key);
            if (null == mv)
                return null;
            if (!ownedVersions.contains(mv.version))
                return null;
            localMap.remove(key);
            ownedVersions.remove(mv.version);
        }
        // TODO(pino,jlm): Should the notify and localMap/ownedVersions updates
        // not happen until it's bounced off ZooKeeper, and happen in the
        // DirectoryWatcher?  (i.e., make this an asyncDelete)
        notifyWatchers(key, mv.value, null);
        dir.delete(encodePath(key, mv.value, mv.version));
        return mv.value;
    }

    private void notifyWatchers(K key, V oldValue, V newValue) {
        for (Watcher<K, V> watcher : watchers) {
            watcher.processChange(key, oldValue, newValue);
        }
    }

    private class MapValue {
        V value;
        int version;

        MapValue(V value, int version) {
            this.value = value;
            this.version = version;
        }
    }

    private class Path {
        K key;
        V value;
        int version;

        Path(K key, V value, int version) {
            this.key = key;
            this.value = value;
            this.version = version;
        }
    }

    private String encodePath(K key, V value, int version) {
        return encodeFullPath(encodeKey(key), encodeValue(value), version);
    }

    protected static String encodeFullPath(String k, String v, int version) {
        StringBuilder strBuilder = new StringBuilder();
        strBuilder.append(encodePathPrefix(k, v)).append(
            String.format("%010d", version));
        return strBuilder.toString();
    }

    protected static String encodePathPrefix(String key, String value) {
        StringBuilder strBuilder = new StringBuilder();
        strBuilder.append('/');
        strBuilder.append(key).append(',');
        strBuilder.append(value).append(',');
        return strBuilder.toString();
    }

    private String encodePath(K key, V value) {
        return encodePathPrefix(encodeKey(key), encodeValue(value));
    }

    protected static String[] getKeyValueVersion(String encodedPath) {
        // Need to skip the '/' at the beginning of the path
        if (encodedPath.startsWith("/"))
            encodedPath = encodedPath.substring(1);
        return encodedPath.split(",");
    }

    private Path decodePath(String str) {
        String[] parts = getKeyValueVersion(str);
        Path result = new Path(null, null, 0);
        result.key = decodeKey(parts[0]);
        result.value = decodeValue(parts[1]);
        result.version = Integer.parseInt(parts[2]);
        return result;
    }

    public synchronized boolean containsValue(V address) {
        for (Map.Entry<K, MapValue> entry : localMap.entrySet())
            if (entry.getValue().value.equals(address))
                return true;

        return false;
    }

    // TODO(pino): document that the encoding may not contain ','.
    protected abstract String encodeKey(K key);

    protected abstract K decodeKey(String str);

    protected abstract String encodeValue(V value);

    protected abstract V decodeValue(String str);
}
