/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman.host;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.zookeeper.CreateMode;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.midonet.config.ConfigProvider;
import org.midonet.midolman.host.config.HostConfig;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.MockDirectory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.version.DataWriteVersion;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Properties;
import java.util.UUID;

public class HostIdGeneratorTest {

    String localPropertiesFile = "host_uuid.properties";
    String uuidPropertyName = "host_uuid";
    String hostId1 = "e3f9adc0-5175-11e1-b86c-0800200c9a66";
    String hostId2 = "e3f9adc0-5175-11e1-b86c-0800200c9a67";
    File propFile;
    HostConfig config;
    HostConfig configFake;

    public class TestModule extends AbstractModule {

        private final String basePath;

        public TestModule(String basePath) {
            this.basePath = basePath;
        }

        @Override
        protected void configure() {
            bind(PathBuilder.class).toInstance(new PathBuilder(basePath));
        }

        @Provides @Singleton
        public Directory provideDirectory(PathBuilder paths) {
            Directory directory = new MockDirectory();
            try {
                directory.add(paths.getBasePath(), null, CreateMode.PERSISTENT);
                directory.add(paths.getWriteVersionPath(),
                        DataWriteVersion.CURRENT.getBytes(),
                        CreateMode.PERSISTENT);
                directory.add(paths.getHostsPath(), null,
                        CreateMode.PERSISTENT);
            } catch (Exception ex) {
                throw new RuntimeException("Could not initialize zk", ex);
            }
            return directory;
        }
    }

    @After
    public void tearDown() throws Exception {
        if (propFile.exists())
            propFile.delete();
    }

    @Before
    public void setUp() throws Exception {
        final HierarchicalConfiguration fakeConfiguration =
                new HierarchicalConfiguration();
        fakeConfiguration.addNodes(HostConfig.GROUP_NAME,
                Arrays.asList(new HierarchicalConfiguration.Node
                        ("properties_file", localPropertiesFile)));
        final HierarchicalConfiguration configuration =
                new HierarchicalConfiguration();

        // this configuration will use the default hostid properties location.
        configuration.addNodes(HostConfig.GROUP_NAME,
                Arrays.asList(new HierarchicalConfiguration.Node
                        (uuidPropertyName, hostId1)
                ));
        propFile = new File(localPropertiesFile);
        config =
                ConfigProvider
                        .providerForIniConfig(configuration)
                        .getConfig(HostConfig.class);
        configFake =
                ConfigProvider
                        .providerForIniConfig(fakeConfiguration)
                        .getConfig(HostConfig.class);

        Properties properties = new Properties();
        properties.setProperty(uuidPropertyName, hostId2);
        properties.store(new FileOutputStream(localPropertiesFile), null);
    }

    @Test
    public void getIdFromConfFile() throws Exception {
        UUID id = HostIdGenerator.getHostId(config);
        Assert.assertEquals(id.toString(), hostId1);
    }

    @Test
    public void getIdFromPropertyFile() throws Exception {
        // pass a conf file with no ID specified so we will try to read it
        // from the properties file
        UUID id = HostIdGenerator.getHostId(configFake);
        Assert.assertTrue(id.toString().equals(hostId2));
    }

    @Test
    public void generateRandomId() throws Exception {
        // delete properties file
        boolean res = propFile.delete();
        Assert.assertTrue(res);
        UUID id = HostIdGenerator.getHostId(configFake);
        // check that the id has been written in the property file
        boolean exists = propFile.exists();
        Assert.assertTrue(exists);
        Properties properties = new Properties();
        properties.load(new FileInputStream(localPropertiesFile));
        UUID idFromProperty = UUID.fromString(
            properties.getProperty(uuidPropertyName));
        Assert.assertTrue(id.equals(idFromProperty));
    }

    @Test(expected = HostIdGenerator.PropertiesFileNotWritableException.class)
    public void propertyFileCorrupted() throws Exception {
        // delete properties file so no ID will be loaded from there
        boolean res = propFile.delete();
        propFile.createNewFile();
        propFile.setReadOnly();
        UUID id = HostIdGenerator.getHostId(configFake);
    }
}
