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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import scala.concurrent.Future;
import scala.concurrent.Promise;

import akka.dispatch.Futures;
import akka.japi.Function2;
import scala.concurrent.Promise$;

import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.util.UUIDUtil;
import org.midonet.midolman.services.HostIdProviderService;
import org.midonet.packets.IPv4Addr;
import org.midonet.util.UnixClock;
import org.midonet.util.concurrent.CallingThreadExecutionContext$;
import org.midonet.util.eventloop.Reactor;

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
    private static final long ONE_DAY = TimeUnit.DAYS.toMillis(1);
    private static final Logger log = LoggerFactory
        .getLogger(ZkNatBlockAllocator.class);

    private final UUID hostId;
    private final ZooKeeper zk;
    private final PathBuilder paths;
    private final UnixClock clock;
    private final Reactor reactor;

    public ZkNatBlockRecycler(ZooKeeper zk,
                              PathBuilder paths,
                              Reactor reactor,
                              HostIdProviderService hostIdService,
                              UnixClock clock) {
        this.reactor = reactor;
        this.hostId = hostIdService.hostId();
        this.zk = zk;
        this.paths = paths;
        this.clock = clock;
    }

    public Future<Integer> recycle() {
        final Promise<Integer> p = Promise$.MODULE$.apply();
        zk.getChildren(paths.getNatPath(), false, new AsyncCallback.Children2Callback() {
            @Override
            public void processResult(int rc, String path,
                                      Object ctx,
                                      final List<String> children,
                                      final Stat stat) {
                if (stat == null) {
                    log.debug("Skipping NAT block recycling: no NAT blocks "
                              + "have been allocated");
                    return;
                }
                if (clock.time() - stat.getMtime() < ONE_DAY) {
                    log.debug("Skipping NAT block recycling: too soon after last operation");
                    p.success(0);
                    return;
                }
                byte[] serializedHostId = new UUIDUtil.RichJavaUuid(hostId).toBytes();
                zk.setData(paths.getNatPath(), serializedHostId, stat.getVersion(),
                           new StatCallback() {
                    @Override
                    public void processResult(int rc, String path, Object ctx,
                                              Stat stat) {
                        if (rc == KeeperException.Code.OK.intValue()) {
                            log.info("Recycling NAT blocks");
                            p.completeWith(recycleDevices(children));
                        } else if (rc == KeeperException.Code.BADVERSION.intValue()) {
                            log.info("Stopping: recycle already being performed by another host");
                            p.success(0);
                        } else {
                            log.info("Failed to start NAT block recycle (ZK error: {})",
                                     KeeperException.Code.get(rc));
                            p.success(0);
                        }
                    }
                }, null);
            }
       }, null);
        return p.future();
    }

    private Future<Integer> recycleDevices(List<String> devices) {
        ArrayList<Future<Integer>> fs = new ArrayList<>(devices.size());
        for (String device : devices) {
            final UUID devId = UUID.fromString(device);
            final String devPath = paths.getNatDevicePath(devId);
            final Promise<Integer> p = Promise$.MODULE$.apply();
            fs.add(p.future());
            zk.getChildren(devPath, false, new AsyncCallback.Children2Callback() {
                @Override
                public void processResult(int rc, String path, Object ctx,
                                          List<String> children, Stat stat) {
                    if (rc == KeeperException.Code.OK.intValue()) {
                        if (children.size() > 0) {
                            p.completeWith(recycleIps(devId, children));
                            return;
                        }
                        maybeDeletePath(devPath, stat.getVersion());
                    } else {
                        log.info("Failed to recycle blocks for device {} (ZK error: {})",
                                 devId, KeeperException.Code.get(rc));
                    }
                    p.success(0);
                }
            }, null);
        }
        return sum(fs);
    }

    private void maybeDeletePath(String path, int version) {
        zk.delete(path, version, new AsyncCallback.VoidCallback() {
            @Override
            public void processResult(int rc, String path, Object ctx) {
            }
        }, null);
    }

    private Future<Integer> recycleIps(final UUID devId, List<String> ips) {
        ArrayList<Future<Integer>> fs = new ArrayList<>(ips.size());
        for (String ip : ips) {
            final IPv4Addr ipv4 = IPv4Addr.apply(ip);
            final String ipPath = paths.getNatIpPath(devId, ipv4);
            final Promise<Integer> p = Promise$.MODULE$.apply();
            fs.add(p.future());
            zk.getData(ipPath, false, new AsyncCallback.DataCallback() {
                @Override
                public void processResult(int rc, String path, Object ctx,
                                          byte[] data, Stat stat) {
                    Op[] deleteOps = new Op[NatBlock.TOTAL_BLOCKS + 1];
                    if (rc == KeeperException.Code.OK.intValue()) {
                        deleteOps[NatBlock.TOTAL_BLOCKS] = Op.delete(path, stat.getVersion());
                        tryRecycleBlocks(devId, ipv4, 0, deleteOps, p);
                    } else {
                        log.info("Failed to recycle blocks for device {} and ip {} (ZK error: {})",
                                 devId, ipv4, KeeperException.Code.get(rc));
                        p.success(0);
                    }
                }
            }, null);
        }
        return sum(fs);
    }

    private void tryRecycleBlocks(final UUID device, final IPv4Addr ip,
                                  final int blockIdx, final Op[] deleteOps,
                                  final Promise<Integer> p) {
        if (blockIdx >= NatBlock.TOTAL_BLOCKS) {
            recycleBlocks(device, ip, deleteOps, p);
            return;
        }

        final String path = paths.getNatBlockPath(device, ip, blockIdx);
        zk.getData(path, false, new AsyncCallback.DataCallback() {
            @Override
            public void processResult(int rc, String path, Object ctx,
                                      byte[] data, Stat stat) {
                if (rc == KeeperException.Code.OK.intValue()
                        && stat.getNumChildren() == 0) {
                    deleteOps[blockIdx] = Op.delete(path, stat.getVersion());
                    tryRecycleBlocks(device, ip, blockIdx + 1, deleteOps, p);
                } else {
                    p.success(0);
                }
            }
        }, null);
    }

    private void recycleBlocks(final UUID device,
                               final IPv4Addr ip,
                               final Op[] deleteOps,
                               final Promise<Integer> p) {
        reactor.submit(new Runnable() {
            @Override
            public void run() {
                int blocks = 0;
                try {
                    zk.multi(Arrays.asList(deleteOps));
                    log.debug("Recycled blocks for device {} and ip {}", device, ip);
                    blocks = NatBlock.TOTAL_BLOCKS;
                } catch (KeeperException.NoNodeException |
                         KeeperException.BadVersionException ignored) {
                } catch (InterruptedException | KeeperException e) {
                    log.error("Failed to recycle blocks for device {} and ip {}: {}", device, ip, e);
                } finally {
                    p.success(blocks);
                }
            }
        });
    }

    private Future<Integer> sum(Iterable<Future<Integer>> fs) {
        return Futures.fold(0, fs, new Function2<Integer, Integer, Integer>() {
            @Override
            public Integer apply(Integer arg1, Integer arg2) throws Exception {
                return arg1 + arg2;
            }
        }, CallingThreadExecutionContext$.MODULE$);
    }
}
