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
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import com.google.inject.Inject;
import com.google.inject.name.Named;

import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.cluster.zookeeper.ZkConnectionProvider;
import org.midonet.midolman.services.HostIdProviderService;
import org.midonet.util.UnixClock;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.functors.Callback;

/**
 * This class uses ZooKeeper to allocate NAT blocks adhering to the following
 * requirements:
 *  - NAT blocks have a fixed size;
 *  - NAT blocks are scoped by device and associated with a given IP;
 *  - A block is randomly chosen from the set of unused NAT blocks;
 *  - If there are no unused blocks, we choose the least recently used free one
 *    (having been freed either explicitly or because its owner host went down).
 *
 *  Refer to the documentation for details on the algorithm.
 */
public class ZkNatBlockAllocator implements NatBlockAllocator {
    private static final List<ACL> acl = Ids.OPEN_ACL_UNSAFE;

    private static final Logger log = LoggerFactory
            .getLogger(ZkNatBlockAllocator.class);

    private final ZooKeeper zk;
    private final PathBuilder paths;
    // TODO: Until ZK 3.5, which supports async multi operations
    private final Reactor reactor;

    @Inject
    public ZkNatBlockAllocator(ZkConnection zk, PathBuilder paths,
                               @Named(ZkConnectionProvider.DIRECTORY_REACTOR_TAG) Reactor reactor,
                               HostIdProviderService hostIdService,
                               UnixClock clock) {
        this.zk = zk.getZooKeeper();
        this.paths = paths;
        this.reactor = reactor;
        final ZkNatBlockRecycler recycler = new ZkNatBlockRecycler(
            this.zk, paths, reactor, hostIdService, clock);
        Runnable recycleBlocks = new Runnable() {
            @Override
            public void run() {
                recycler.recycle();
                ZkNatBlockAllocator.this.reactor.schedule(this, 5, TimeUnit.HOURS);
            }
        };
        int factor = ThreadLocalRandom.current().nextInt(5) + 1;
        reactor.schedule(recycleBlocks, factor, TimeUnit.HOURS);
    }

    @Override
    public void allocateBlockInRange(final NatRange natRange,
                                     final Callback<NatBlock, Exception> callback) {
        log.debug("Trying to allocate a suitable block for {}", natRange);
        reactor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    allocateBlock(natRange, callback);
                } catch (InterruptedException ignored) {
                } catch (KeeperException e) {
                    if (e.code() == KeeperException.Code.NODEEXISTS) {
                        // Retry, but allow the reactor to process other work
                        allocateBlockInRange(natRange, callback);
                    } else if (e.code() == KeeperException.Code.NONODE) {
                        ensureDevicePath(natRange, callback);
                    } else {
                        callback.onError(e);
                    }
                }
            }
        });
    }

    // TODO: Use support for multi-get in ZK 3.5
    private void allocateBlock(NatRange natRange,
                               Callback<NatBlock, Exception> callback)
            throws KeeperException, InterruptedException {
        int lruBlock = -1;
        long lruBlockZxid = Long.MAX_VALUE;
        ArrayList<Integer> virginBlocks = new ArrayList<>();
        int startBlock = natRange.tpPortStart / NatBlock.BLOCK_SIZE;
        int endBlock = natRange.tpPortEnd / NatBlock.BLOCK_SIZE;
        Stat stat = new Stat();
        for (int i = startBlock; i <= endBlock; ++i) {
            String path = paths.getNatBlockPath(natRange.deviceId, natRange.ip, i);
            zk.getData(path, false, stat);
            if (stat.getNumChildren() == 0) {
                // Pzxid is the (undocumented) zxid of the last modified child
                long pzxid = stat.getPzxid();
                if (pzxid == stat.getCzxid()) {
                    virginBlocks.add(i);
                } else if (pzxid < lruBlockZxid) {
                    lruBlockZxid = pzxid;
                    lruBlock = i;
                }
            }
        }

        if (virginBlocks.size() > 0) {
            int block = ThreadLocalRandom.current().nextInt(0, virginBlocks.size());
            claimBlock(virginBlocks.get(block), natRange, callback);
        } else if (lruBlock >= 0) {
            claimBlock(lruBlock, natRange, callback);
        } else {
            callback.onSuccess(NatBlock.NO_BLOCK);
        }
    }

    private void claimBlock(final int block,
                            final NatRange natRange,
                            final Callback<NatBlock, Exception> callback)
            throws KeeperException, InterruptedException {
        log.debug("Trying to claim block {} for {}", block, natRange);
        String path = paths.getNatBlockOwnershipPath(
            natRange.deviceId, natRange.ip, block);
        zk.create(path, null, acl, CreateMode.EPHEMERAL);
        callback.onSuccess(new NatBlock(natRange.deviceId, natRange.ip, block));
    }

    @Override
    public void freeBlock(NatBlock natBlock) {
        freeBlock(natBlock, 10);
    }

    private void freeBlock(final NatBlock natBlock, final int retries) {
        log.debug("Freeing {} ({} retries left)", natBlock, retries);
        String path = paths.getNatBlockOwnershipPath(
            natBlock.deviceId, natBlock.ip, natBlock.blockIndex);
        zk.delete(path, -1, new AsyncCallback.VoidCallback() {
            @Override
            public void processResult(int rc, String path, Object ctx) {
                if (rc != KeeperException.Code.OK.intValue() &&
                    rc != KeeperException.Code.NONODE.intValue()) {
                    log.debug("Failed to free {}", natBlock);
                    if (retries > 0)
                        freeBlock(natBlock, retries - 1);
                }
            }
        }, null);
    }

    private void ensureDevicePath(final NatRange natRange,
                                  final Callback<NatBlock, Exception> callback) {
        zk.create(paths.getNatDevicePath(natRange.deviceId), null,
                  acl, CreateMode.PERSISTENT,
                  new AsyncCallback.StringCallback() {
                      @Override
                      public void processResult(int rc, String path, Object ctx,
                                                String name) {
                          if (rc == KeeperException.Code.OK.intValue() ||
                              rc == KeeperException.Code.NODEEXISTS.intValue()) {
                              ensureIpPath(natRange, callback);
                          } else {
                              callback.onError(KeeperException.create(
                                  KeeperException.Code.get(rc), path));
                          }
                      }
                  }, null);
    }

    private void ensureIpPath(final NatRange natRange,
                              final Callback<NatBlock, Exception> callback) {
        String path = paths.getNatIpPath(natRange.deviceId, natRange.ip);
        final List<Op> dirs = new ArrayList<>(NatBlock.TOTAL_BLOCKS + 1);
        dirs.add(Op.create(path, null, acl, CreateMode.PERSISTENT));
        initializeBlockDirectories(dirs, natRange);
        reactor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    zk.multi(dirs);
                    allocateBlockInRange(natRange, callback);
                } catch (InterruptedException ignored) {
                } catch (KeeperException e) {
                    int error = e.getResults().get(0).getType();
                    if (error == KeeperException.Code.NODEEXISTS.intValue()) {
                        allocateBlockInRange(natRange, callback);
                    } else {
                        callback.onError(e);
                    }
                }
            }
        });
    }

    private void initializeBlockDirectories(List<Op> blockDirs, NatRange natRange) {
        for (int i = 0; i < NatBlock.TOTAL_BLOCKS; ++i) {
            String path = paths.getNatBlockPath(natRange.deviceId, natRange.ip, i);
            blockDirs.add(Op.create(path, null, acl, CreateMode.PERSISTENT));
        }
    }
}
