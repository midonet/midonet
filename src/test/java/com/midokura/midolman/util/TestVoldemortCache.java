package com.midokura.midolman.util;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import voldemort.server.VoldemortConfig;
import voldemort.server.VoldemortServer;

/**
 * Test VoldemortCache.  Also used to set up test Voldemort servers.
 * As used as latter, must always invoke {@link tearDownVoldemort}
 * after finishing the tests which had invoked {@link setUpVoldemort}.
 * These methods can only be invoked on an instance constructed separately.
 * 
 * Several temporary directories in /tmp are created during the tests,
 * which are deleted when the Voldemort servers are torn down.
 * 
 * @author Yoo Chung
 */
public class TestVoldemortCache {

    private static final String tmpPrefix = "/tmp/midovold-";
    
    private static final long lifetime = 100L;
    
    private static final Random random = new Random();
    
    private File[] tmpDirs = new File[3];
    private VoldemortServer[] servers = new VoldemortServer[3];
    
    private void setUpVoldemortServer(int i) throws IOException {
        String tmpName = tmpPrefix + random.nextInt(1000000);
        File tmpDir = new File(tmpName);
        tmpDir.mkdir();
        
        new File(tmpName + "/config").mkdir();
        
        FileWriter writer = new FileWriter(tmpName + "/config/cluster.xml");
        writer.write(clusterXMLContent);
        writer.close();
        
        writer = new FileWriter(tmpName + "/config/server.properties");
        writer.write(String.format(serverPropertiesFormat, i, lifetime));
        writer.close();
        
        writer = new FileWriter(tmpName + "/config/stores.xml");
        writer.write(storesXMLContent);
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
    public void setUpVoldemort() throws Exception {
        for (int i = 0; i < 3; i++)
            setUpVoldemortServer(i);
    }

    /** Return list of URLs for connecting to test Voldemort servers. */
    public List<String> bootstrapURLs() {
        return Arrays.asList(new String[] {
           "tcp://localhost:16666",
           "tcp://localhost:16668",
           "tcp://localhost:16670"
        });
    }
    
    /** Minimum lifetime in milliseconds used by test Voldemort servers. */
    public long lifetime() {
        return lifetime;
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
    
    private void tearDownVoldemortServer(int i) {
        if (servers[i].isStarted())
            servers[i].stop();
        
        deleteDirectory(tmpDirs[i]);
    }
    
    /**
     * Tear down Voldemort servers that were set up for test purposes.
     */
    public void tearDownVoldemort() {
        for (int i = 0; i < 3; i++)
            tearDownVoldemortServer(i);
    }
    
    /* 
     * The actual tests for VoldemortCache are below.
     * The stuff above are for setting up and tearing down tests,
     * either for the tests here or for other tests which wish to
     * use Voldemort servers.
     */
    
    private VoldemortCache cache;
    
    @Before
    public void setUp() throws Exception {
        setUpVoldemort();
        cache = new VoldemortCache("midonat", 
                (int)(lifetime() / 1000), 
                bootstrapURLs());
    }

    @After
    public void tearDown() throws Exception {
        cache = null;
        tearDownVoldemort();
    }

    @Test
    public void testGetMissing() {
        assertNull(cache.get("test_key"));
    }
    
    @Test
    public void testSet() {
        cache.set("test_key", "test_value");
    }

    @Test
    public void testSetAndGet() {
        cache.set("test_key", "test_value");
        assertEquals("test_value", cache.get("test_key"));
    }
    
    @Test
    public void testSetReplace() {
        cache.set("test_key", "test_value1");
        cache.set("test_key", "test_value2");
        assertEquals("test_value2", cache.get("test_key"));
    }
    
    @Test
    public void testExpireSingle() throws Exception {
        cache.set("test_key", "test_value");
        Thread.sleep(3 * lifetime());
        assertNull(cache.get("test_key"));
    }
    
    @Test
    public void testRefresh() throws Exception {
        cache.set("test_key", "test_value");
        
        // repeat refreshes until at least 3*lifetime()
        // if refresh does not work, this will ensure expiration
        Thread.sleep(lifetime() / 2);
        assertEquals("test_value", cache.getAndTouch("test_key"));
        Thread.sleep(lifetime() / 2);
        assertEquals("test_value", cache.getAndTouch("test_key"));
        Thread.sleep(lifetime() / 2);
        assertEquals("test_value", cache.getAndTouch("test_key"));
        Thread.sleep(lifetime() / 2);
        assertEquals("test_value", cache.getAndTouch("test_key"));
        Thread.sleep(lifetime() / 2);
        assertEquals("test_value", cache.getAndTouch("test_key"));
        Thread.sleep(lifetime() / 2);
        assertEquals("test_value", cache.get("test_key"));
    }
    
    /*
     * It would be preferable to instantiate Voldemort servers programmatically.
     * Unfortunately, a lot of configuration (also called 'metadata') has to be
     * stored in files, so might as well go all the way.
     */
    
    private static final String clusterXMLContent =
            "<cluster>"
            + "<name>midonet</name>"
            + "<server>"
            + "<id>0</id>"
            + "<host>localhost</host>"
            + "<http-port>8081</http-port>"
            + "<socket-port>16666</socket-port>"
            + "<admin-port>16667</admin-port>"
            + "<partitions>0, 1</partitions>"
            + "</server>"
            + "<server>"
            + "<id>1</id>"
            + "<host>localhost</host>"
            + "<http-port>8082</http-port>"
            + "<socket-port>16668</socket-port>"
            + "<admin-port>16669</admin-port>"
            + "<partitions>2, 3</partitions>"
            + "</server>"
            + "<server>"
            + "<id>2</id>"
            + "<host>localhost</host>"
            + "<http-port>8083</http-port>"
            + "<socket-port>16670</socket-port>"
            + "<admin-port>16671</admin-port>"
            + "<partitions>4, 5</partitions>"
            + "</server>"
            + "</cluster>";
    
    private static final String serverPropertiesFormat =
            "node.id=%d\n"
            + "amnesic.lifetime=%d\n"
            + "socket.enable=true\n"
            + "jmx.enable=false\n"
            + "enable.nio.connector=true\n"
            + "request.format=vp3\n"
            + "slop.store.engine=amnesic\n"
            + "storage.configs=com.midokura.midolman.voldemort.AmnesicStorageConfiguration\n";
    
    private static final String storesXMLContent =
            "<stores>"
            + "<store>"
            + "<name>midonat</name>"
            + "<persistence>amnesic</persistence>"
            + "<routing>client</routing>"
            + "<replication-factor>2</replication-factor>"
            + "<required-reads>2</required-reads>"
            + "<required-writes>2</required-writes>"
            + "<key-serializer><type>string</type></key-serializer>"
            + "<value-serializer><type>string</type></value-serializer>"
            + "</store>"
            + "</stores>";

}
