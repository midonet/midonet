/*
 * Copyright 2015 Midokura SARL
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
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;

import org.midonet.packets.IPv4Addr;
import org.midonet.util.UnixClock;

/**
 * Recycles unused blocks belonging to a particular (device, ip) tuple.
 * It only recycles iff all the blocks under a particular ip are unused.
 *
 * Note that a very rare race condition can occur:
 *    1) a port migrates, along with connections using some NAT bindings with
 *       ports from blocks belonging to the host from which the block is migrating
 *    2) a recycle operation happens and the block containing the NAT binding is
 *       freed, along with all other blocks under that IP (so no host was using them)
 *    3) another host grabs that block by randomly choosing it over all the blocks
 *       for that IP
 *    4) a connection for the same dst ip is created and the host simulating that
 *       connection randomly chooses the same port as an existing, migrated connection
 */
public class ZkNatBlockRecycler {
    private final ZooKeeper zk;
    private final PathBuilder paths;
    private final UnixClock clock;

    public ZkNatBlockRecycler(ZooKeeper zk, PathBuilder paths, UnixClock clock) {
        this.zk = zk;
        this.paths = paths;
        this.clock = clock;
    }

    public int recycle() throws KeeperException, InterruptedException {
        int deletedBlocks = 0;
        List<String> devices = zk.getChildren(paths.getNatPath(), false);
        for (String device : devices) {
            UUID devId = UUID.fromString(device);
            try {
                String devPath = paths.getNatDevicePath(devId);
                Stat stat = new Stat();
                List<String> ips = zk.getChildren(devPath, false, stat);
                if (ips.size() > 0) {
                    deletedBlocks += recyclePerIp(devId, ips);
                } else {
                    zk.delete(devPath, stat.getVersion());
                }
            } catch (KeeperException e) {
                if (e.code() != KeeperException.Code.NONODE &&
                    e.code() != KeeperException.Code.BADVERSION)
                    throw e;
            }
        }
        return deletedBlocks;
    }

    private int recyclePerIp(UUID devId, List<String> ips)
            throws KeeperException, InterruptedException {
        int deletedBlocks = 0;
        for (String ip : ips) {
            ArrayList<Op> deleteOps = new ArrayList<>(NatBlock.TOTAL_BLOCKS + 1);
            IPv4Addr ipv4 = IPv4Addr.apply(ip);
            String ipPath = paths.getNatIpPath(devId, ipv4);
            Stat stat = new Stat();
            try {
                zk.getData(ipPath, false, stat);
                if (shouldDelete(devId, ipv4, deleteOps)) {
                    deleteOps.add(Op.delete(ipPath, stat.getVersion()));
                    zk.multi(deleteOps);
                    deletedBlocks += NatBlock.TOTAL_BLOCKS;
                }
            } catch (KeeperException e) {
                if (e.code() != KeeperException.Code.NONODE &&
                    e.code() != KeeperException.Code.BADVERSION)
                    throw e;
            }
        }
        return deletedBlocks;
    }

    private boolean shouldDelete(UUID device, IPv4Addr ip, List<Op> deleteOps)
                throws KeeperException, InterruptedException {
        Stat stat = new Stat();
        long currentTime = clock.time();
        for (int i = 0; i < NatBlock.TOTAL_BLOCKS; ++i) {
            String path = paths.getNatBlockPath(device, ip, i);
            zk.getData(path, false, stat);
            int ONE_HOUR = 60 * 60 * 1000;
            if (stat.getNumChildren() > 0 || currentTime - stat.getMtime() < ONE_HOUR)
                return false;
            deleteOps.add(Op.delete(path, stat.getVersion()));
        }
        return true;
    }
}
