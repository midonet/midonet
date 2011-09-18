/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Options;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.HierarchicalINIConfiguration;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.eventloop.SelectListener;
import com.midokura.midolman.eventloop.SelectLoop;
import com.midokura.midolman.openflow.ControllerStubImpl;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.ZkConnection;

public class Midolman implements SelectListener, Watcher {

    static final Logger log = LoggerFactory.getLogger(Midolman.class);

    private HierarchicalConfiguration config;
    
    private int disconnected_ttl_seconds;
    private ScheduledFuture disconnected_kill_timer = null;
    
    private ScheduledExecutorService executor;
    private OpenvSwitchDatabaseConnection ovsdb;
    private ZkConnection zkConnection;
    private ServerSocketChannel listenSock;
    
    private SelectLoop loop;
    
    private Directory midonetDirectory;
    
    private Midolman() {}
    
    private void run(String[] args) throws Exception {
        log.info("main start");
        
        Options options = new Options();
        options.addOption("c", "configFile", true, "config file path");
        CommandLineParser parser = new GnuParser();
        CommandLine cl = parser.parse(options, args);
        
        String configFilePath = cl.getOptionValue('c', "./conf/midolman.conf");

        config = new HierarchicalINIConfiguration(configFilePath);
        Configuration midolmanConfig = config.configurationAt("midolman");

        disconnected_ttl_seconds = midolmanConfig.getInteger("disconnected_ttl_seconds", 30);

        executor = Executors.newScheduledThreadPool(1);

        // open the OVSDB connection
        ovsdb = new OpenvSwitchDatabaseConnectionImpl(
                "OpenvSwitch", 
                config.configurationAt("openvswitch").getString("openvswitchdb_ip_addr", "127.0.0.1"), 
                config.configurationAt("openvswitch").getInt("openvswitchdb_tcp_port", 6634));

        zkConnection = new ZkConnection(
                config.configurationAt("zookeeper").getString("zookeeper_hosts", "127.0.0.1:2181"), 
                config.configurationAt("zookeeper").getInt("session_timeout", 30000), this);

        log.debug("about to ZkConnection.open()");
        zkConnection.open();
        log.debug("done with ZkConnection.open()");

        midonetDirectory = zkConnection.getRootDirectory().getSubDirectory(midolmanConfig.getString("midolman_root_key"));

        listenSock = ServerSocketChannel.open();
        listenSock.configureBlocking(false);
        listenSock.socket().bind(
                new java.net.InetSocketAddress(config.configurationAt("openflow").getInt("controller_port", 6633)));

        loop = new SelectLoop(executor);

        loop.register(listenSock, SelectionKey.OP_ACCEPT, this);
        
        log.debug("before doLoop which will block");
        loop.doLoop();
        log.debug("after doLoop is done");
        
        log.info("main finish");
    }
    
    @Override
    public void handleEvent(SelectionKey key) throws IOException {
        log.info("handleEvent " + key);

        try {
            SocketChannel sock = listenSock.accept();
            log.info("handleEvent acccepted connection from " + sock.socket().getRemoteSocketAddress());

            sock.socket().setTcpNoDelay(true);
            sock.configureBlocking(false);

            Directory midoDir = zkConnection.getRootDirectory().getSubDirectory(
                    config.configurationAt("midolman").getString("midolman_root_key"));

            ControllerTrampoline trampoline = new ControllerTrampoline(config, ovsdb, midonetDirectory, loop);
            ControllerStubImpl controllerStubImpl = new ControllerStubImpl(sock, loop, trampoline);
            
            SelectionKey switchKey = loop.registerBlocking(sock, SelectionKey.OP_READ, controllerStubImpl);
            
            switchKey.interestOps(SelectionKey.OP_READ);
            loop.wakeup();
            
            controllerStubImpl.start();
        } catch (Exception e) {
            log.warn("handleEvent", e);
        }
    }
    
    @Override
    public synchronized void process(WatchedEvent event) {
        if (event.getState() == Watcher.Event.KeeperState.Disconnected) {
            log.warn("KeeperState is Disconnected, shutdown soon");

            disconnected_kill_timer = executor.schedule(new Runnable() {
                @Override
                public void run() {
                    log.error("have been disconnected for {} " + "seconds, so exiting", disconnected_ttl_seconds);
                    System.exit(-1);
                }
            }, disconnected_ttl_seconds, TimeUnit.SECONDS);
        }

        if (event.getState() == Watcher.Event.KeeperState.SyncConnected) {
            log.info("KeeperState is SyncConnected");

            if (disconnected_kill_timer != null) {
                log.info("canceling shutdown");
                disconnected_kill_timer.cancel(true);
                disconnected_kill_timer = null;
            }
        }

        if (event.getState() == Watcher.Event.KeeperState.Expired) {
            log.warn("KeeperState is Expired, shutdown now");
            System.exit(-1);
        }
    }

    public static void main(String[] args) {
        try {
            new Midolman().run(args);
        } catch (Exception e) {
            log.error("main caught", e);
            System.exit(-1);
        }
    }

}
