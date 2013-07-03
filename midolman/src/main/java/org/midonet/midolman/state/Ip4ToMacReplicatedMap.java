/* Copyright 2012 Midokura Inc. */

package org.midonet.midolman.state;

import java.util.HashMap;
import java.util.Map;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;

import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

public class Ip4ToMacReplicatedMap extends ReplicatedMap<IPv4Addr, MAC> {

    public Ip4ToMacReplicatedMap(Directory dir) {
        super(dir);
    }

    @Override
    protected String encodeKey(IPv4Addr key) {
        return key.toString();
    }

    @Override
    protected IPv4Addr decodeKey(String str) {
        return IPv4Addr.fromString(str);
    }

    @Override
    protected String encodeValue(MAC value) {
        return value.toString();
    }

    @Override
    protected MAC decodeValue(String str) {
        return MAC.fromString(str);
    }

    public static Map<IPv4Addr, MAC> getAsMap(Directory dir)
        throws StateAccessException {
        try {
            Iterable<String> paths = dir.getChildren("/", null);
            Map<IPv4Addr, MAC> m = new HashMap<IPv4Addr, MAC>();
            for (String path : paths) {
                String[] parts = ReplicatedMap.getKeyValueVersion(path);
                // TODO(pino): consider the version too.
                m.put(IPv4Addr.fromString(parts[0]), MAC.fromString(parts[1]));
            }
            return m;
        } catch (KeeperException e) {
            throw new StateAccessException(e);
        } catch (InterruptedException e) {
            throw new StateAccessException(e);
        }
    }

    public static boolean hasPersistentEntry(Directory dir, IPv4Addr key,
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

    public static void addPersistentEntry(Directory dir, IPv4Addr key,
                                          MAC value)
            throws StateAccessException {
        // Use version 1 for all persistent entries added to the map.
        // This avoids having to enumerate the map entries in order to delete.
        String path = ReplicatedMap.encodeFullPath(
            key.toString(), value.toString(), 1);
        try {
            dir.add(path, null, CreateMode.PERSISTENT);
        } catch (KeeperException e) {
            throw new StateAccessException(e);
        } catch (InterruptedException e) {
            throw new StateAccessException(e);
        }
    }

    public static void deletePersistentEntry(Directory dir, IPv4Addr key,
                                             MAC value)
            throws StateAccessException {
        // Version 1 is used for all persistent entries added to the map.
        // This avoids having to enumerate the map entries in order to delete.
        String path = ReplicatedMap.encodeFullPath(
            key.toString(), value.toString(), 1);
        try {
            dir.delete(path);
        } catch (KeeperException e) {
            throw new StateAccessException(e);
        } catch (InterruptedException e) {
            throw new StateAccessException(e);
        }
    }
}
