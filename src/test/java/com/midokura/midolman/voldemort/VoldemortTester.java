/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.voldemort;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.midokura.midolman.util.VoldemortCache;

import voldemort.server.VoldemortConfig;
import voldemort.server.VoldemortServer;

/**
 * Set up and tear down test Voldemort servers. Must always invoke
 * {@link tearDown} after finishing the tests which had invoked
 * {@link setUp}.  Typical usage would be:
 *
 * <pre>
 * private VoldemortTester voldemort;
 * private VoldemortCache cache;
 *
 * public void setUp() {
 *     ...
 *     voldemort = new VoldemortTester();
 *     voldemort.setUp();
 *     cache = voldemort.constructCache();
 *     ...
 * }
 *
 * public void tearDown() {
 *     ...
 *     voldemort.tearDown();
 *     ...
 * }
 * </pre>
 *
 * Several temporary directories in /tmp are created during the tests, which
 * are deleted when the Voldemort servers are torn down.
 *
 * @author Yoo Chung
 */
public class VoldemortTester {

    /** Prefix for the temporary directories. */
    private static final String tmpPrefix = "/tmp/midovold-";

    /** Default name of Voldemort stores used in tests. */
    public static final String STORENAME = "midotest";

    /** Default minimum lifetime of items in milliseconds. */
    public static final long LIFETIME = 200L;

    /** Default number of Voldemort servers to instantiate. */
    public static final int INSTANCES = 4;

    private static final Random random = new Random();

    private String name;
    private long lifetime;
    private int instances;

    private File[] tmpDirs;
    private VoldemortServer[] servers;

    public VoldemortTester() {
        this(STORENAME, LIFETIME, INSTANCES);
    }

    public VoldemortTester(String storeName, long lifetime, int instances) {
        this.name = storeName;
        this.lifetime = lifetime;
        this.instances = instances;
        this.tmpDirs = new File[instances];
        this.servers = new VoldemortServer[instances];
    }

    private void setUpServer(int i) throws IOException {
        String tmpName = tmpPrefix + random.nextInt(1000000);
        File tmpDir = new File(tmpName);
        tmpDir.mkdir();

        new File(tmpName + "/config").mkdir();

        FileWriter writer = new FileWriter(tmpName + "/config/cluster.xml");
        writer.write(clusterXMLPrefix);
        for (int j = 0; j < instances; j++)
            writer.write(String.format(clusterXMLServerFormat,
                    j,
                    8081 + j,
                    16666 + 2 * j,
                    16667 + 2 * j,
                    2 * j,
                    2 * j + 1));
        writer.write(clusterXMLPostfix);
        writer.close();

        writer = new FileWriter(tmpName + "/config/server.properties");
        writer.write(String.format(serverPropertiesFormat, i, lifetime));
        writer.close();

        writer = new FileWriter(tmpName + "/config/stores.xml");
        writer.write(String.format(storesXMLFormat, name));
        writer.close();

        VoldemortConfig config = VoldemortConfig.loadFromVoldemortHome(tmpName);
        VoldemortServer server = new VoldemortServer(config);
        if (!server.isStarted())
            server.start();

        this.servers[i] = server;
        this.tmpDirs[i] = tmpDir;
    }

    /**
     * Set up Voldemort servers for test purposes.
     *
     * @throws Exception
     */
    public void setUp() throws Exception {
        for (int i = 0; i < instances; i++)
            setUpServer(i);
    }

    private void deleteDirectory(File dir) {
        assert dir.isDirectory();
        File[] files = dir.listFiles();
        for (File f : files) {
            if (f.isFile()) {
                f.delete();
            } else {
                assert f.isDirectory();
                deleteDirectory(f);
            }
        }

        dir.delete();
    }

    private void tearDownServer(int i) {
        if (servers[i].isStarted())
            servers[i].stop();

        deleteDirectory(tmpDirs[i]);
    }

    /**
     * Tear down Voldemort servers that were set up for test purposes.
     */
    public void tearDown() {
        for (int i = 0; i < instances; i++)
            tearDownServer(i);

        servers = null;
        tmpDirs = null;
    }

    /** Direct access to the servers. */
    public VoldemortServer[] servers() {
        return servers;
    }

    /** Return list of URLs for connecting to test Voldemort servers. */
    public List<String> bootstrapURLs() {
        ArrayList<String> urls = new ArrayList<String>(instances);

        for (int i = 0; i < instances; i++)
            urls.add("tcp://localhost:" + (16666 + 2 * i));

        return urls;
    }

    /** Minimum lifetime in milliseconds used by test Voldemort servers. */
    public long lifetime() {
        return lifetime;
    }

    /** Name of store used in tests. */
    public String storeName() {
        return name;
    }

    /** Construct cache interface to test Voldemort servers. */
    public VoldemortCache constructCache() {
        return new VoldemortCache(storeName(),
                (int)(lifetime() / 1000),
                bootstrapURLs());
    }

    /*
     * It would be preferable to instantiate Voldemort servers programmatically.
     * Unfortunately, a lot of configuration (also called 'metadata') has to be
     * stored in files, so might as well go all the way.
     */

    private static final String clusterXMLPrefix =
            "<cluster><name>midonet</name>";

    private static final String clusterXMLPostfix = "</cluster>";

    private static final String clusterXMLServerFormat =
            "<server>"
            + "<id>%d</id>"
            + "<host>localhost</host>"
            + "<http-port>%d</http-port>"
            + "<socket-port>%d</socket-port>"
            + "<admin-port>%d</admin-port>"
            + "<partitions>%d, %d</partitions>"
            + "</server>";

    private static final String serverPropertiesFormat =
            "node.id=%d\n"
            + "amnesic.lifetime=%d\n"
            + "socket.enable=true\n"
            + "jmx.enable=false\n"
            + "enable.nio.connector=true\n"
            + "request.format=vp3\n"
            + "slop.store.engine=amnesic\n"
            + "storage.configs=com.midokura.midolman.voldemort.AmnesicStorageConfiguration\n";

    private static final String storesXMLFormat =
            "<stores>"
            + "<store>"
            + "<name>%s</name>"
            + "<persistence>amnesic</persistence>"
            + "<routing>client</routing>"
            + "<replication-factor>3</replication-factor>"
            + "<required-reads>2</required-reads>"
            + "<required-writes>2</required-writes>"
            + "<key-serializer><type>string</type></key-serializer>"
            + "<value-serializer><type>string</type></value-serializer>"
            + "</store>"
            + "</stores>";

}
