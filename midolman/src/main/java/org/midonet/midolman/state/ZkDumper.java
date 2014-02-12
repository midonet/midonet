/**
 * ZkDumper.java - simple command line utility to dump data from ZooKeeper.
 *
 * Run with:
 *       mvn exec:java -Dexec.mainClass=org.midonet.midolman.state.ZkDumper
 *                     -Dexec.args=<arguments>
 * TODO: Package this into a standalone .jar
 *
 * Copyright 2011 Midokura Inc.
 */

package org.midonet.midolman.state;

import java.util.List;
import java.util.concurrent.Semaphore;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooKeeper;

public class ZkDumper {

    static ZooKeeper zk;
    static final Semaphore available = new Semaphore(0);

    public static void main(String args[]) {
        Options options = new Options();
        options.addOption("h", "host", true, "ZooKeeper server hostname");
        options.addOption("p", "port", true, "ZooKeeper server port");
        HelpFormatter formatter = new HelpFormatter();
        CommandLineParser parser = new GnuParser();
        CommandLine cl;
        try {
            cl = parser.parse(options, args);
        } catch (ParseException e) {
            System.err.println("Bad command arguments: " + e);
            formatter.printHelp("ZkDumper [path]*", options);
            System.exit(-1);
            cl = null;  // javac thinks cl used uninitialized because it
                        // doesn't know .exit() is no-return.
        }

        int zkPort = Integer.parseInt(cl.getOptionValue('p', "2181"));
        String zkHost = cl.getOptionValue('h', "localhost");

        List<String> argList = cl.getArgList();
        if (argList.isEmpty()) {
            argList.add("/");
        }

        try {
            setupZKConnection(zkHost, zkPort);
        } catch (Exception e) {
            System.err.println("Failed to establish ZooKeeper connection: " + e);
            System.exit(-1);
        }

        try {
            for (String path : argList) {
                byte[] data = zk.getData(path, false, null);
                String dataStr = (data == null) ? null : new String(data);
                System.out.println("\n" + path + ": " + dataStr);
                dumpSubTree(path, 0);
            }
        } catch (Exception e) {
            System.err.println("Error dumping tree: " + e);
            System.exit(-1);
        }

        try {
            zk.close();
        } catch (Exception e) {
            System.err.println("Error closing connection: " + e);
            System.exit(-1);
        }

    }

    private static void setupZKConnection(final String host, final int port) 
                throws Exception {
        int magic = 3000;  // FIXME
        System.out.println("Connecting to ZooKeeper at " + host+":"+port);
        zk = new ZooKeeper(host+":"+port, magic, 
                new Watcher() {
                    @Override
                    public synchronized void process(WatchedEvent event) {
                        if (event.getState() == KeeperState.Disconnected) {
                            System.err.println("Disconnected from ZooKeeper");
                            System.exit(-1);
                        } else if (event.getState() == KeeperState.SyncConnected) {
                            System.out.println("Connected to ZooKeeper at " + host+":"+port);
                            available.release();
                        } else if (event.getState() == KeeperState.Expired) {
                            System.err.println("Session expired");
                            System.exit(-1);
                        }
                    }
                });
        System.out.println("In progress to ZooKeeper at " + host+":"+port);
        
        available.acquire();
    }

    static void dumpSubTree(String path, int level) throws Exception {
        List<String> children = zk.getChildren(path, false);
        for (String child : children) {
            String childPath = path + (path.endsWith("/") ? "" : "/") + child;
            byte[] data = zk.getData(childPath, false, null);
            String dataStr = (data == null) ? null : new String(data);
            StringBuilder sb = new StringBuilder();
            for (int i=0; i<level; i++) {
                sb.append('\t');
            }
            sb.append(child).append(" => ").append(dataStr);
            System.out.println(sb.toString());
            dumpSubTree(childPath, level+1);
        }
    }
}
