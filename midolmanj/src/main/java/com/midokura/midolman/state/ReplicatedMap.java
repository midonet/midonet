/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.state;

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
    
    private final static Logger log = LoggerFactory.getLogger(ReplicatedMap.class);

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

    private class DirectoryWatcher implements Runnable {
        public void run() {
            if (!running) {
                return;
            }
            Set<String> curPaths = null;
            try {
                curPaths = dir.getChildren("/", this);
            } catch (KeeperException e) {
                log.error("DirectoryWatcher.run", e);
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                log.error("DirectoryWatcher.run", e);
                Thread.currentThread().interrupt();
            }
            List<String> cleanupPaths = new LinkedList<String>();
            Set<K> curKeys = new HashSet<K>();
            for (String path : curPaths) {
                Path p = decodePath(path);
                curKeys.add(p.key);
                MapValue mv = map.get(p.key);
                /*
                 * TODO(pino): if(null == mv || mv.version < p.version) This way
                 * of determining the winning value is flawed: if the controller
                 * that writes the most recent value fails some maps may never
                 * see it. They will be inconsistent with maps that saw the most
                 * recent value. The fix is to choose the winning value based on
                 * the highest version in ZK. Only use the most recent version
                 * this map remembers in order to decide whether to notify our
                 * watchers.
                 */
                if (null == mv || mv.version < p.version) {
                    map.put(p.key, new MapValue(p.value, p.version, false));
                    if (null == mv)
                        notifyWatchers(p.key, null, p.value);
                    else {
                        // Remember my obsolete paths and clean them up later.
                        if (mv.owner)
                            cleanupPaths.add(encodePath(p.key, mv.value,
                                    mv.version));
                        notifyWatchers(p.key, mv.value, p.value);
                    }
                }
            }
            Set<K> allKeys = new HashSet<K>(map.keySet());
            allKeys.removeAll(curKeys);
            // The remaining keys must have been deleted by someone else.
            for (K key : allKeys) {
                MapValue mv = map.remove(key);
                if (null != mv.value)
                    notifyWatchers(key, mv.value, null);
            }
            // Now clean up any of my paths that have been obsoleted.
            for (String path : cleanupPaths)
                try {
                    dir.delete(path);
                } catch (KeeperException e) {
                    log.error("DirectoryWatcher.run", e);
                    throw new RuntimeException(e);
                } catch (InterruptedException e) {
                    log.error("DirectoryWatcher.run", e);
                    Thread.currentThread().interrupt();
                }
        }
    }

    private Directory dir;
    private boolean running;
    private Map<K, MapValue> map;
    private Set<Watcher<K, V>> watchers;
    private DirectoryWatcher myWatcher;

    public ReplicatedMap(Directory dir) {
        this.dir = dir;
        this.running = false;
        this.map = new HashMap<K, MapValue>();
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

    public void stop() {
        this.running = false;
        this.map.clear();
    }

    public V get(K key) {
        MapValue mv = map.get(key);
        if (null == mv)
            return null;
        return mv.value;
    }

    public boolean containsKey(K key) {
        return map.containsKey(key);
    }

    public Map<K, V> getMap() {
        Map<K, V> result = new HashMap<K, V>();
        for (Map.Entry<K, MapValue> entry : map.entrySet())
            result.put(entry.getKey(), entry.getValue().value);
        return result;
    }

    public List<K> getByValue(V value) {
        ArrayList<K> keyList = new ArrayList<K>();
        for (Map.Entry<K, MapValue> entry : map.entrySet())
            if (entry.getValue().value.equals(value))
                keyList.add(entry.getKey());
        return keyList;
    }

    public void put(K key, V value) throws KeeperException,
            InterruptedException {
        MapValue oldMv = map.get(key);
        String path = dir.add(new Path(key, value, 0).encode(false), null,
                CreateMode.EPHEMERAL_SEQUENTIAL);
        // Get the sequence number added by ZooKeeper.
        Path p = decodePath(path);
        map.put(key, new MapValue(value, p.version, true));

        if (null != oldMv && oldMv.owner) {
            try {
                dir.delete(encodePath(key, oldMv.value, oldMv.version));
            } catch (KeeperException.NoNodeException e) {
                // Ignore this exception. The watcher may already have been
                // triggered and it cleaned up this old node.
            }
        }
        if (null != oldMv) {
            if (!value.equals(oldMv.value))
                notifyWatchers(key, oldMv.value, value);
        } else if (value != null)
            notifyWatchers(key, null, value);
    }

    public V remove(K key) throws KeeperException, InterruptedException {
        MapValue mv = map.get(key);
        if (null == mv)
            return null;
        notifyWatchers(key, mv.value, null);
        map.remove(key);
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
        boolean owner;

        MapValue(V value, int version, boolean owner) {
            this.value = value;
            this.version = version;
            this.owner = owner;
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

        String encode(boolean withVersion) {
            if (withVersion)
                return encodePath(key, value, version);
            else
                return encodePath(key, value);
        }
    }

    private String encodePath(K key, V value, int version) {
        StringBuilder strBuilder = new StringBuilder();
        strBuilder.append(encodePath(key, value)).append(
                String.format("%010d", version));
        return strBuilder.toString();
    }

    private String encodePath(K key, V value) {
        StringBuilder strBuilder = new StringBuilder();
        strBuilder.append('/');
        strBuilder.append(encodeKey(key)).append(',');
        strBuilder.append(encodeValue(value)).append(',');
        return strBuilder.toString();
    }

    private Path decodePath(String str) {
        // Need to skip the '/' at the beginning of the path
        if (str.startsWith("/"))
            str = str.substring(1);
        String[] parts = str.split(",");
        Path result = new Path(null, null, 0);
        result.key = decodeKey(parts[0]);
        result.value = decodeValue(parts[1]);
        result.version = Integer.parseInt(parts[2]);
        return result;
    }

    public boolean containsValue(V address) {
        for (Map.Entry<K, MapValue> entry : map.entrySet())
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
