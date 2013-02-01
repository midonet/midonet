package org.midonet.midolman.zookeeper;

import java.util.List;
import java.util.concurrent.Semaphore;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZooKeeperDumpTree {
    
    private final static Logger log = LoggerFactory.getLogger(ZooKeeperDumpTree.class);
    
    static ZooKeeper zk;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        final Semaphore available = new Semaphore(0);

        zk = new ZooKeeper("localhost:2181", 3000, new Watcher() {

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
    public void dumpTree() throws Exception {
        dumpSubTree("/", 0);
    }
    
    private void dumpSubTree(String path, int level) throws Exception {
        
        List<String> children = zk.getChildren(path, false);
        for(String child : children) {
            String childPath = (path.length() == 1) ? path + child : path + "/" + child;
            
            byte[] data = zk.getData(childPath, false, null);
            
            String dataStr = null;
            if (data != null) {
                dataStr = new String(data);
            }
            
            StringBuilder sb = new StringBuilder();
            for (int i=0; i<level; i++) {
                sb.append('\t');
            }
            
            log.info("{}{} => {}", new Object[] {sb.toString(), child, dataStr});
            
            dumpSubTree(childPath, level + 1);
        }
        
    }

}
