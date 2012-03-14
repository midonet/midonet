// Copyright 2012 Midokura Inc.

package com.midokura.midolman.state;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.packets.IntIPv4;


public class PortSetMap {
    private final static Logger log = LoggerFactory.getLogger(PortSetMap.class);

    private Directory dir;
    private boolean running;
    private Map<UUID, IPv4Set> map;

    public PortSetMap(Directory dir) {
        this.dir = dir;
        this.running = false;
        this.map = new HashMap<UUID, IPv4Set>();
        //XXX this.myWatcher = new DirectoryWatcher();
    }

    public void stop() {
        this.running = false;
        this.map.clear();
    }

    public IPv4Set get(UUID key) {
        return map.get(key);
    }

    public boolean containsKey(UUID key) {
        return map.containsKey(key);
    }

    public void createPortSet(UUID key)
            throws KeeperException, InterruptedException {
        // TODO: Implement
    }

    public void addIPv4AddrToSet(UUID key, IntIPv4 addr) 
            throws KeeperException, InterruptedException,
                   InvalidStateOperationException {
        IPv4Set ipv4Set = map.get(key);
        if (ipv4Set == null) {
            throw new InvalidStateOperationException("No portset with ID " + 
                                                     key.toString());
        }
        ipv4Set.add(addr);
    }

}
