package com.midokura.midolman.zookeeper;

import java.util.concurrent.Semaphore;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.util.Percentile;

public class ZooKeeperWriteLatency {
    
    private final static Logger log = LoggerFactory.getLogger(ZooKeeperWriteLatency.class);
    
    static ZooKeeper zk;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        final Semaphore available = new Semaphore(0);

        zk = new ZooKeeper("localhost:2181", 3000, new Watcher() {

            @Override
            public synchronized void process(WatchedEvent event) {
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
    public void testWrites() throws Exception {
        Percentile p = new Percentile();
        
        byte b[] = new byte[1000];
        for (int i=0; i<10000; i++) {
            long before = System.nanoTime();
            zk.create("/"+i, b, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
            long after = System.nanoTime();
            
            long delta = after - before;
            
            p.sample(delta);
        }
        
        log.info("create latency");
        log.info("50p = " + p.getPercentile(0.50));
        log.info("70p = " + p.getPercentile(0.70));
        log.info("80p = " + p.getPercentile(0.80));
        log.info("90p = " + p.getPercentile(0.90));
        log.info("100p = " + p.getPercentile(1.00));
        
//        p = new Percentile();
//        
//        byte b2[] = new byte[1000];
//        for (int i=0; i<1000; i++) {
//            long before = System.nanoTime();
//            Stat s = zk.setData("/"+i, b2, -1);
//            long after = System.nanoTime();
//            
//            long delta = after - before;
//            
//            p.sample(delta);
//        }
//        
//        log.info("setData latency");
//        log.info("50p = " + p.getPercentile(0.50));
//        log.info("70p = " + p.getPercentile(0.70));
//        log.info("80p = " + p.getPercentile(0.80));
//        log.info("90p = " + p.getPercentile(0.90));
//        log.info("100p = " + p.getPercentile(1.00));
        
        p = new Percentile();
        
        for (int i=0; i<10000; i++) {
            long before = System.nanoTime();
            zk.getData("/"+i, null, null);
            long after = System.nanoTime();
            
            long delta = after - before;
            
            p.sample(delta);
        }
        
        log.info("getData latency");
        log.info("50p = " + p.getPercentile(0.50));
        log.info("70p = " + p.getPercentile(0.70));
        log.info("80p = " + p.getPercentile(0.80));
        log.info("90p = " + p.getPercentile(0.90));
        log.info("100p = " + p.getPercentile(1.00));
    }

}
