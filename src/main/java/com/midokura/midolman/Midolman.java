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

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.HierarchicalINIConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;
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

class ZkWatcher implements Watcher {
    static final Logger log = LoggerFactory.getLogger(ZkWatcher.class);
    static final int disconnected_ttl_seconds = 
				Midolman.disconnected_ttl_seconds;
    static final ScheduledExecutorService executor = Midolman.executor;
    ScheduledFuture disconnected_kill_timer = null;
                                                        
    @Override
    public synchronized void process(WatchedEvent event) {
        // TODO Auto-generated method stub
        if (event.getState() == Watcher.Event.KeeperState.Disconnected) {
            log.warn("KeeperState is Disconnected, shutdown soon");
                                                            
            disconnected_kill_timer = executor.schedule(
                new Runnable() {
                        @Override
                        public void run() {
                            log.error("have been disconnected for {} " +
                                      "seconds, so exiting", 
                                      disconnected_ttl_seconds);
                            System.exit(-1);
                        }
                 }, 
                 disconnected_ttl_seconds, 
                 TimeUnit.SECONDS);
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
}

public class Midolman {

    static final Logger log = LoggerFactory.getLogger(Midolman.class);

    static int disconnected_ttl_seconds;
    static ScheduledExecutorService executor;
    
    /**
     * @param args
     */
    public static void main(String[] args) {
        try {
            log.info("main start");
    
            final HierarchicalConfiguration config = 
                new HierarchicalINIConfiguration("./conf/midolman.conf");
            
            disconnected_ttl_seconds = 
                config.configurationAt("midolman").getInteger(
                    "disconnected_ttl_seconds", 30);
            
            executor = Executors.newScheduledThreadPool(1);
            
            // open the OVSDB connection
            final OpenvSwitchDatabaseConnection ovsdb = 
                new OpenvSwitchDatabaseConnectionImpl(
                        "OpenvSwitch",
                        config.configurationAt("openvswitch").getString(
                            "openvswitchdb_ip_addr", "127.0.0.1"),
                        config.configurationAt("openvswitch").getInt(
                            "openvswitchdb_tcp_port", 6634)
                    );

            final ZkConnection zkConnection = 
                new ZkConnection(
                        config.configurationAt("zookeeper").getString(
                            "zookeeper_hosts", "127.0.0.1:2181"),
			new ZkWatcher());
    
            log.debug("about to ZkConnection.open()");
            zkConnection.open();
            log.debug("done with ZkConnection.open()");
    
            final ServerSocketChannel listenSock = ServerSocketChannel.open();
            listenSock.configureBlocking(false);
            listenSock.socket().bind(
                new java.net.InetSocketAddress(
                        config.configurationAt("openflow")
			      .getInt("controller_port", 6633)));
    
            final SelectLoop loop = new SelectLoop(executor);
    
            loop.register(listenSock, SelectionKey.OP_ACCEPT,
                new SelectListener() {
                        @Override
                        public void handleEvent(SelectionKey key) 
			        throws IOException {
                    	    log.info("handleEvent " + key);
    
                    	    try {
                        	SocketChannel sock = listenSock.accept();
        
                        	log.info("handleEvent acccepted connection " +
					 "from " + 
					 sock.socket().getRemoteSocketAddress()
					);
        
                        	sock.socket().setTcpNoDelay(true);
                        	sock.configureBlocking(false);
        
				Directory midoDir = 
				    zkConnection.getRootDirectory()
				    .getSubDirectory(
					config.configurationAt("midolman")
					.getString("midonet_root_key", 
						   "/zk_root"));
                        	ControllerStubImpl controllerStubImpl = 
				    new ControllerStubImpl(sock, loop,
                                	    new ControllerTrampoline(
						    config, ovsdb, 
						    midoDir));
        
                        	loop.registerBlocking(sock, SelectionKey.OP_READ, controllerStubImpl);
                    	    } catch (Exception e) {
                        	log.warn("handleEvent", e);
                    	    }
                	}
    
            });
    
            log.debug("before doLoop which will block");
            loop.doLoop();
            log.debug("after doLoop is done");
    
            log.info("main finish");
        } catch (Exception e) {
            log.error("main caught", e);
            System.exit(-1);
        }
    }

}
