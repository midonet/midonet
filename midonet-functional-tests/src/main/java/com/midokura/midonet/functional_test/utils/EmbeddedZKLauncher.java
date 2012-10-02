package com.midokura.midonet.functional_test.utils;

import org.apache.zookeeper.server.NIOServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.Random;

public class EmbeddedZKLauncher {

    private final static Logger log = LoggerFactory
            .getLogger(EmbeddedZKLauncher.class);

    private static final int MAX_CONNECTIONS = 2000;

    static NIOServerCnxnFactory standaloneServerFactory;
    static File dir;

    public static int start(int port) {
        int tickTime = 2000;

        try {
            String dataDirectory = System.getProperty("java.io.tmpdir");
            dir = new File(dataDirectory, "zookeeper"+new Random().nextInt()).getAbsoluteFile();
            ZooKeeperServer server = new ZooKeeperServer(dir, dir, tickTime);
            standaloneServerFactory = new NIOServerCnxnFactory();
            standaloneServerFactory.configure(new InetSocketAddress(port), MAX_CONNECTIONS);
            standaloneServerFactory.startup(server); // start the server.
            log.info("Embedded zookeper server started.");
        } catch (Exception e) {
            log.error("Unable to start the embedded zookeeper server: {}", e);
            return 0;
        }
        return port;
    }

    public static void stop() {
        if (standaloneServerFactory != null) {
            log.info("Stopping the embedded zookeeper server");
            standaloneServerFactory.shutdown();
            dir.delete();
        }
    }
}
