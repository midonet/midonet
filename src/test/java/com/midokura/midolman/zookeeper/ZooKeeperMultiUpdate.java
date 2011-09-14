package com.midokura.midolman.zookeeper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.OpResult;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZooKeeperMultiUpdate {
    
    private final static Logger log = LoggerFactory.getLogger(ZooKeeperMultiUpdate.class);
    
    static ZooKeeper zk;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        final Semaphore available = new Semaphore(0);

        zk = new ZooKeeper("localhost:2181", 3000, new Watcher() {

            ScheduledFuture disconnected_kill_timer = null;

            @Override
            public synchronized void process(WatchedEvent event) {
                // TODO Auto-generated method stub
                if (event.getState() == Watcher.Event.KeeperState.Disconnected) {
                    log.warn("Watcher.Event.KeeperState.Disconnected");
                    System.exit(-1);
                }

                if (event.getState() == Watcher.Event.KeeperState.SyncConnected) {
                    available.release();
                }

                if (event.getState() == Watcher.Event.KeeperState.Expired) {
                    log.warn("Watcher.Event.KeeperState.Expired");
                    System.exit(-1);
                }
            }
        });

        available.acquire();

        log.info("after acquire");
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
        log.info("before close");
        zk.close();
    }

    @Test
    public void testMulti() throws Exception {
        List<Op> ops1 = new ArrayList<Op>();
        ops1.add(Op.create("/1", null, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL));
        ops1.add(Op.create("/2", null, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL));
        ops1.add(Op.create("/3", null, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL));

        List<OpResult> results1 = zk.multi(ops1);
        
        for (OpResult res : results1) {
            log.info("res: " + res);
        }

        Stat s1 = zk.exists("/1", false);
        Assert.assertNotNull(s1);
        
        Stat s2 = zk.exists("/2", false);
        Assert.assertNotNull(s2);
        
        Stat s3 = zk.exists("/3", false);
        Assert.assertNotNull(s3);
        
        byte b[] = new byte[1000];
        zk.setData("/2", b, s2.getVersion());
        
        long before = System.currentTimeMillis();
        for (int i=0; i<100; i++) {
            zk.getData("/2", false, null);
        }
        long after = System.currentTimeMillis();
        
        log.info("duration {}", after - before);
        
        List<Op> ops2 = new ArrayList<Op>();
        ops2.add(Op.delete("/1", s1.getVersion()));
        ops2.add(Op.delete("/2", s2.getVersion()));
        ops2.add(Op.delete("/3", s3.getVersion()));
        
        try {
            List<OpResult> results2 = zk.multi(ops2);
        } catch (KeeperException e) {
            log.warn("multi", e);
        }
        
        Assert.assertNotNull(zk.exists("/1", false));
        Assert.assertNotNull(zk.exists("/2", false));
        Assert.assertNotNull(zk.exists("/3", false));
        
        s2 = zk.exists("/2", false);
        
        List<Op> ops3 = new ArrayList<Op>();
        ops3.add(Op.delete("/1", s1.getVersion()));
        ops3.add(Op.delete("/2", s2.getVersion()));
        ops3.add(Op.delete("/3", s3.getVersion()));
        
        List<OpResult> results3 = zk.multi(ops3);
        
        Assert.assertNull(zk.exists("/1", false));
        Assert.assertNull(zk.exists("/2", false));
        Assert.assertNull(zk.exists("/3", false));
    }

}
