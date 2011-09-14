package com.midokura.midolman.state;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;

public abstract class ReplicatedMap<K, V> {

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
                e.printStackTrace();
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                e.printStackTrace();
                Thread.currentThread().interrupt();
            }
            List<String> cleanupPaths = new LinkedList<String>();
            Set<K> curKeys = new HashSet<K>();
            for (String path : curPaths) {
                Path p = decodePath(path);
                curKeys.add(p.key);
                MapValue mv = map.get(p.key);
                if(null == mv || mv.version < p.version) {
                    map.put(p.key, new MapValue(p.value, p.version, false));
                    if(null == mv)
                        notifyWatchers(p.key, null, p.value);
                    else {
                        // Remember my obsolete paths and clean them up later.
                        if(mv.owner)
                            cleanupPaths.add(encodePath(p.key, mv.value, mv.version));
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
                    e.printStackTrace();
                    throw new RuntimeException(e);
                } catch (InterruptedException e) {
                    e.printStackTrace();
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

    public Map<K,V> getMap() {
        Map<K,V> result = new HashMap<K,V>();
        for (Map.Entry<K, MapValue> entry : map.entrySet())
            result.put(entry.getKey(), entry.getValue().value);
        return result;
    }

    public void put(K key, V value) throws KeeperException, InterruptedException {
        MapValue oldMv = map.get(key);
        String path = dir.add(new Path(key, value, 0).encode(false), null,
                CreateMode.EPHEMERAL_SEQUENTIAL);
        // Get the sequence number added by ZooKeeper.
        Path p = decodePath(path);
        map.put(key, new MapValue(value, p.version, true));

        if (null != oldMv && oldMv.owner) {
            try {
                dir.delete(encodePath(key, oldMv.value, oldMv.version));
            }
            catch (KeeperException.NoNodeException e) {
                // Ignore this exception. The watcher may already have been
                // triggered and it cleaned up this old node.
            }
        }
        if (null != oldMv) {
            if (!value.equals(oldMv.value))
                notifyWatchers(key, oldMv.value, value);
        }
        else if (value != null)
            notifyWatchers(key, null, value);
    }

    public V remove(K key) throws KeeperException, InterruptedException {
        MapValue mv = map.get(key);
        if (null == mv)
            return null;
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
        strBuilder.append(encodePath(key, value)).append(version);
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
            if (entry.getValue().value == address)
                return true;

        return false;
    }

    // TODO(pino): document that the encoding may not contain ','.
    protected abstract String encodeKey(K key);
    protected abstract K decodeKey(String str);
    protected abstract String encodeValue(V value);
    protected abstract V decodeValue(String str);

}
