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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.backend.Directory;
import org.midonet.cluster.backend.DirectoryCallback;
import org.midonet.cluster.backend.zookeeper.ZkConnectionAwareWatcher;

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
        /**
         * Retrieve all the children of the watched directory, handling
         * failures.
         * @return the set of paths, or null if a failure happened
         */
        Set<String> getCurPaths() {
            Set<String> paths = null;
            try {
                // TODO(pino, rossella): make this asynchronous.
                paths = dir.getChildren("/", this);
            } catch (KeeperException e) {
                log.warn("DirectoryWatcher.run {}", e);
                if (connectionWatcher == null) {
                    throw new RuntimeException(e);
                }
                connectionWatcher.handleError("ReplicatedMap", this, e);
            } catch (InterruptedException e) {
                log.error("DirectoryWatcher.run {}", e);
                Thread.currentThread().interrupt();
            }
            return paths;
        }

        /**
         * Takes the curPaths set and populates the given newMap with them,
         * using only the highest versioned entry for each key, and adding to
         * cleanupPaths all that need to be purged.
         */
        void populateNewMap(final Map<K, MapValue> newMap,
                            final Set<String> curPaths,
                            final List<Path> cleanupPaths) {
            for (String path : curPaths) {
                Path p = decodePath(path);
                MapValue mv = newMap.get(p.key);
                if (mv == null)
                    newMap.put(p.key, new MapValue(p.value, p.version));
                else if (mv.version < p.version) {
                    // The one currently in newMap needs to be replaced.
                    // Also clean it up if it belongs to this ZK client.
                    newMap.put(p.key, new MapValue(p.value, p.version));

                    if (ownedVersions.contains(mv.version)) {
                        p.value = mv.value;
                        p.version = mv.version;
                        cleanupPaths.add(p);
                    }
                } else if (mv.version > p.version &&
                           ownedVersions.contains(p.version)) {
                    // The one currently in newMap is newer and the other
                    // one belongs to this ZK client. Clean it up.
                    cleanupPaths.add(p);
                }
            }
        }

        /**
         * Cleans all paths in the given List. If a ZK exception occurs in the
         * process it will abort the cleanup. InterruptedExceptions will
         * stop the running thread.
         *
         * @param paths to clean up
         */
        void cleanup(final List<Path> paths) {
            for (Path path : paths) {
                try {
                    dir.delete(encodePath(path.key, path.value, path.version));
                } catch (KeeperException e) {
                    log.error("DirectoryWatcher.run", e);
                    // TODO (guillermo) connectionWatcher.handleError()?
                    throw new RuntimeException(e);
                } catch (InterruptedException e) {
                    log.error("DirectoryWatcher.run", e);
                    Thread.currentThread().interrupt();
                }
            }
            synchronized(ReplicatedMap.this) {
                for (Path path : paths) {
                    ownedVersions.remove(path.version);
                }
            }
        }

        /**
         * Compiles notifications to be sent after the newMap has been compiled.
         *
         * @param notifications where to accumulate the notifications
         * @param newMap the new map generated from the last set of paths
         */
        void collectNotifications(final Set<Notification<K, V>> notifications,
                                  final Map<K, MapValue> newMap) {

            Set<K> oldKeys = new HashSet<>(localMap.keySet());
            oldKeys.removeAll(newMap.keySet());
            for (K deletedKey : oldKeys) {
                MapValue mv = localMap.get(deletedKey);
                notifications.add(new Notification<>(
                    deletedKey, mv.value, null));
            }

            for (Map.Entry<K, MapValue> entry : newMap.entrySet()) {
                K key = entry.getKey();
                V value = entry.getValue().value;
                MapValue mv = localMap.get(key);
                if (mv == null) {
                    notifications.add(new Notification<>(
                        key, null, value));
                } else if (mv.version != entry.getValue().version) {
                    // We compare versions because the 'value' members
                    // might not implement .equals accurately.
                    notifications.add(new Notification<>(
                        key, mv.value, value));
                } // else mv == entry:  No notification.
            }

        }

        public void run() {
            if (!running.get()) {
                return;
            }
            Set<String> curPaths = getCurPaths();
            if (curPaths == null)
                return;

            List<Path> cleanupPaths = new LinkedList<>();
            Set<Notification<K,V>> notifications = new HashSet<>();

            synchronized(ReplicatedMap.this) {
                if (!running.get()) {
                    return;
                }
                ConcurrentMap<K,MapValue> newMap = new ConcurrentHashMap<>();
                populateNewMap(newMap, curPaths, cleanupPaths);
                collectNotifications(notifications, newMap);
                localMap = newMap;
            }

            for (Notification<K,V> notice : notifications) {
                notifyWatchers(notice.key, notice.oldValue, notice.newValue);
            }

            cleanup(cleanupPaths);
        }
    }

    private Directory dir;
    private AtomicBoolean running;
    private volatile ConcurrentMap<K, MapValue> localMap;
    private Set<Integer> ownedVersions;
    private Set<Watcher<K, V>> watchers;
    private DirectoryWatcher myWatcher;
    private boolean createsEphemeralNode;

    public ReplicatedMap(Directory dir) {
        this(dir, true);
    }

    /**
     * ReplicatedMap constructor takes a Directory of key / value lists, and a
     * boolean indicating whether it creates an ephemeral entry on the backend
     * for a key-value pair that is 'put' to the map.
     * @param dir A ZooKeeper directory (node) under which key-value pairs are
     * stored.
     * @param ephemeral A boolean indicating whether a key-value pair that's
     * 'put' to this map be made an ephemeral / persistent ZooKeeper node.
     */
    public ReplicatedMap(Directory dir, boolean ephemeral) {
        this.dir = dir;
        this.running = new AtomicBoolean(false);
        this.localMap = new ConcurrentHashMap<>();
        this.ownedVersions = new HashSet<>();
        this.watchers = new HashSet<>();
        this.myWatcher = new DirectoryWatcher();
        this.createsEphemeralNode = ephemeral;
    }

    public void addWatcher(Watcher<K, V> watcher) {
        watchers.add(watcher);
    }

    public void removeWatcher(Watcher<K, V> watcher) {
        watchers.remove(watcher);
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            myWatcher.run();
        }
    }

    public synchronized void stop() {
        running.set(false);
        Map<K, MapValue> oldMap = localMap;
        localMap = new ConcurrentHashMap<>();
        oldMap.clear();
    }

    public V get(K key) {
        MapValue mv = localMap.get(key);
        return (null == mv) ? null : mv.value;
    }

    public boolean containsKey(K key) {
        return localMap.containsKey(key);
    }

    /**
     * Provides <pre>Map</pre> with a copy of the local map.
     */
    public Map<K, V> getMap() {
        Map<K, MapValue> snapshot = localMap;
        Map<K, V> result = new HashMap<>();
        for (Map.Entry<K, MapValue> entry : snapshot.entrySet())
            result.put(entry.getKey(), entry.getValue().value);
        return result;
    }

    /**
     * Synchronized method to retrieve the list of keys associated with the
     * given <pre>value</pre> in the local map.
     *
     * @param value the value whose keys want to be found
     * @return the list of keys. Empty if none.
     */
    public synchronized List<K> getByValue(V value) {
        Map<K, MapValue> snapshot = localMap;
        List<K> keyList = new ArrayList<>();
        for (Map.Entry<K, MapValue> entry : snapshot.entrySet()) {
            if (entry.getValue().value.equals(value))
                keyList.add(entry.getKey());
        }
        return keyList;
    }

    private class PutCallback implements DirectoryCallback<String> {
        private final K key;
        private final V value;

        PutCallback(K k, V v) {
            key = k;
            value = v;
        }

        public void onSuccess(String result) {
            // Claim the sequence number added by ZooKeeper.
            Path p = decodePath(result);
            synchronized(ReplicatedMap.this) {
                ownedVersions.add(p.version);
            }
        }

        public void onError(KeeperException ex) {
            log.error("Put {} => {} failed: {}", key, value, ex);
        }

        public void onTimeout() {
            log.error("Put {} => {} timed out.", key, value);
        }
    }

    private class DeleteCallBack implements DirectoryCallback<Void> {
        private final K key;
        private final V value;
        private final int version;

        DeleteCallBack(K k, V v, int ver) {
            key = k;
            value = v;
            version = ver;
        }

        public void onSuccess(Void result) {
            synchronized(ReplicatedMap.this) {
                /* The map entry that was just removed from Zookeeper was or
                   will be deleted from the local map in method run(). This is
                   true if no value is inserted for the same key in the
                   meantime. Watchers will be notified of this deletion in
                   method run as well. */
                ownedVersions.remove(version);
            }
        }

        private Runnable deleteRunnable() {
            final DeleteCallBack thisCb = this;
            return new Runnable() {
                public void run() {
                    dir.asyncDelete(encodePath(key, value, version), thisCb);
                }
            };
        }

        public void onError(KeeperException ex) {
            String opDesc = "Replicated map deletion of key: " + key;
            connectionWatcher.handleError(opDesc, deleteRunnable(), ex);
        }

        public void onTimeout() {
            log.info("Deletion of key: {} timed out, retrying.", key);
            connectionWatcher.handleTimeout(deleteRunnable());
        }
    }

    /**
     * Asynchronous add to associate <pre>key</pre> to <pre>value</pre> in the
     * map.
     *
     * Our notifies for this change are called from the update notification to
     * the DirectoryWatcher after ZK has accepted it.
     */
    public void put(final K key, final V value) {
        String path = this.createsEphemeralNode ? encodePath(key, value)
                : encodeFullPath(key.toString(), value.toString(),
                                 Integer.MAX_VALUE);
        CreateMode mode = this.createsEphemeralNode ?
                CreateMode.EPHEMERAL_SEQUENTIAL : CreateMode.PERSISTENT;

        dir.asyncAdd(path, null, mode, new PutCallback(key, value));
    }

    /**
     * Check <pre>key</pre> ownership.
     *
     * Synchronized.
     *
     * @return true if the key's <pre>MapValue</pre> version is owned here.
     */
    public synchronized boolean isKeyOwner(K key) {
        MapValue mv = localMap.get(key);
        return null != mv && ownedVersions.contains(mv.version);
    }

    /**
     * This is essentially the same as removeIfOwner(K key), but will also
     * verify that the value being deleted is <pre>val</pre>. This helps prevent
     * races where value A is replaced with B, then a pending deletion for A
     * comes and wipes off B (by key).
     *
     * If a deletion actually happened, this will notify all watchers that the
     * new value for <pre>key</pre> is null.
     *
     * The get and verifications are performed synchronously blocking on the
     * map itself. Notifications and deletion from the ZK directory are not.
     *
     * NOTE: when val is null, the behaviour will be the same as removeIfOwner,
     * that is, it'll delete whatever value is there.
     *
     * @param key the key
     * @param ensureVal value that you're trying to delete
     * @return the value that was deleted (val) or null if either the key
     *         was not in the map, the version was not owned, or the value was
     *         different
     * @throws KeeperException
     * @throws InterruptedException
     */
    public V removeIfOwnerAndValue(K key, V ensureVal)
        throws KeeperException, InterruptedException {
        MapValue mv;
        synchronized(this) {
            mv = localMap.get(key);
            if (null == mv)
                return null;
            if (!ownedVersions.contains(mv.version))
                return null;
            if ((ensureVal != null) && !mv.value.equals(ensureVal))
                return null;

        }
        dir.asyncDelete(encodePath(key, mv.value, mv.version),
                        new DeleteCallBack(key, mv.value, mv.version));
        return mv.value;
    }

    /**
     * Removes the entry with <pre>key</pre>, notifying all watchers with the
     * removed value. It'll delete wahtever value is associated for the key.
     *
     * The get and verifications are performed synchronously blocking on the
     * map itself. Notifications and deletion from the ZK directory are not.
     *
     * @throws KeeperException
     * @throws InterruptedException
     */
    public V removeIfOwner(final K key)
        throws KeeperException, InterruptedException {
        return removeIfOwnerAndValue(key, null);
    }

    private void notifyWatchers(final K key, final V oldValue,
                                final V newValue) {
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

        @Override
        public String toString() {
            return "{value: " + value + ", version: " + version + "}";
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

    public synchronized boolean containsValue(V val) {
        Map<K, MapValue> snapshot = localMap;
        for (Map.Entry<K, MapValue> entry : snapshot.entrySet())
            if (entry.getValue().value.equals(val))
                return true;

        return false;
    }

    /**
     * Encodes <pre>key</pre>.
     *
     * Note that the encoding key may not contain ','.
     *
     * TODO: document that limitation (where?)
     */
    protected abstract String encodeKey(K key);

    protected abstract K decodeKey(String str);

    protected abstract String encodeValue(V value);

    protected abstract V decodeValue(String str);
}
