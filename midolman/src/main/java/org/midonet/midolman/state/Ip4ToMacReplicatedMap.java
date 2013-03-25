/* Copyright 2012 Midokura Inc. */

package org.midonet.midolman.state;

import java.util.HashMap;
import java.util.Map;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;

import org.midonet.packets.IntIPv4;
import org.midonet.packets.MAC;

public class Ip4ToMacReplicatedMap extends ReplicatedMap<IntIPv4, MAC> {

    public Ip4ToMacReplicatedMap(Directory dir) {
        super(dir);
    }

    private IntIPv4 unicastIPv4(IntIPv4 ip) {
        return (ip.getMaskLength() != 32) ?
            new IntIPv4(ip.getAddress(), 32) : ip;
    }

    @Override
    public MAC get(IntIPv4 key) {
        return super.get(unicastIPv4(key));
    }

    @Override
    public boolean containsKey(IntIPv4 key) {
        return super.containsKey(unicastIPv4(key));
    }

    @Override
    public void put(IntIPv4 key, MAC value) {
        super.put(unicastIPv4(key), value);
    }

    @Override
    public MAC removeIfOwner(IntIPv4 key)
        throws InterruptedException, KeeperException {
        return super.removeIfOwner(unicastIPv4(key));
    }

    @Override
    public synchronized boolean isKeyOwner(IntIPv4 key) {
        return super.isKeyOwner(unicastIPv4(key));
    }

    @Override
    protected String encodeKey(IntIPv4 key) {
        return key.toUnicastString();
    }

    @Override
    protected IntIPv4 decodeKey(String str) {
        return IntIPv4.fromString(str);
    }

    @Override
    protected String encodeValue(MAC value) {
        return value.toString();
    }

    @Override
    protected MAC decodeValue(String str) {
        return MAC.fromString(str);
    }

    public static Map<IntIPv4, MAC> getAsMap(Directory dir)
        throws StateAccessException {
        try {
            Iterable<String> paths = dir.getChildren("/", null);
            Map<IntIPv4, MAC> m = new HashMap<IntIPv4, MAC>();
            for (String path : paths) {
                String[] parts = ReplicatedMap.getKeyValueVersion(path);
                // TODO(pino): consider the version too.
                m.put(IntIPv4.fromString(parts[0]), MAC.fromString(parts[1]));
            }
            return m;
        } catch (KeeperException e) {
            throw new StateAccessException(e);
        } catch (InterruptedException e) {
            throw new StateAccessException(e);
        }
    }

    public static boolean hasPersistentEntry(Directory dir, IntIPv4 key,
                                             MAC value)
            throws StateAccessException {
        // Version 1 is used for all persistent entries added to the map.
        // This avoids having to enumerate the map entries in order to delete.
        String path = ReplicatedMap.encodeFullPath(
            key.toString(), value.toString(), 1);
        try {
            return dir.has(path);
        } catch (KeeperException e) {
            throw new StateAccessException(e);
        } catch (InterruptedException e) {
            throw new StateAccessException(e);
        }
    }

    public static void addPersistentEntry(Directory dir, IntIPv4 key,
                                          MAC value)
            throws StateAccessException {
        // Use version 1 for all persistent entries added to the map.
        // This avoids having to enumerate the map entries in order to delete.
        String path = ReplicatedMap.encodeFullPath(
            key.toUnicastString(), value.toString(), 1);
        try {
            dir.add(path, null, CreateMode.PERSISTENT);
        } catch (KeeperException e) {
            throw new StateAccessException(e);
        } catch (InterruptedException e) {
            throw new StateAccessException(e);
        }
    }

    public static void deletePersistentEntry(Directory dir, IntIPv4 key,
                                             MAC value)
            throws StateAccessException {
        // Version 1 is used for all persistent entries added to the map.
        // This avoids having to enumerate the map entries in order to delete.
        String path = ReplicatedMap.encodeFullPath(
            key.toUnicastString(), value.toString(), 1);
        try {
            dir.delete(path);
        } catch (KeeperException e) {
            throw new StateAccessException(e);
        } catch (InterruptedException e) {
            throw new StateAccessException(e);
        }
    }
}
