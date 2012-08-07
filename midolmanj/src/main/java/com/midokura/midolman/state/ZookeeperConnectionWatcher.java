/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.state;

import com.midokura.midolman.config.MidolmanConfig;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ZookeeperConnectionWatcher implements Watcher {

    static final Logger log = LoggerFactory.getLogger(ZookeeperConnectionWatcher.class);

    private ScheduledExecutorService executorService;
    private ScheduledFuture<?> disconnectHandle;

    @Inject
    MidolmanConfig config;

    public ZookeeperConnectionWatcher() {
        executorService = Executors.newScheduledThreadPool(1);
    }

    @Override
    public synchronized void process(WatchedEvent event) {
        if (event.getState() == Watcher.Event.KeeperState.Disconnected) {
            log.warn("KeeperState is Disconnected, shutdown soon");

            disconnectHandle = executorService.schedule(new Runnable() {
                @Override
                public void run() {
                    log.error("have been disconnected for {} seconds, " +
                            "so exiting", config.getZooKeeperGraceTime());
                    System.exit(-1);
                }
            }, config.getZooKeeperGraceTime(), TimeUnit.SECONDS);
        }

        if (event.getState() == Watcher.Event.KeeperState.SyncConnected) {
            log.info("KeeperState is SyncConnected");

            if (disconnectHandle != null) {
                log.info("canceling shutdown");
                disconnectHandle.cancel(true);
                disconnectHandle = null;
            }
        }

        if (event.getState() == Watcher.Event.KeeperState.Expired) {
            log.warn("KeeperState is Expired, shutdown now");
            System.exit(-1);
        }

        //TODO(abel) should this class process other Zookeeper events?
    }
}
