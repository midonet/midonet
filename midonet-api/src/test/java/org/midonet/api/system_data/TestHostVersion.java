/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.system_data;

import java.net.URI;
import java.util.UUID;

import com.google.inject.*;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;
import org.apache.zookeeper.KeeperException;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import org.midonet.api.rest_api.FuncTest;
import org.midonet.api.serialization.SerializationModule;
import org.midonet.api.zookeeper.StaticMockDirectory;
import org.midonet.client.MidonetApi;
import org.midonet.client.resource.HostVersion;
import org.midonet.client.resource.ResourceCollection;
import org.midonet.client.VendorMediaType;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.version.DataWriteVersion;
import org.midonet.midolman.version.guice.VersionModule;

public class TestHostVersion extends JerseyTest {

    private static Injector injector;
    private MidonetApi api;
    public static final String ZK_ROOT_MIDOLMAN = "/test/midolman";

    private HostZkManager hostManager;

    public TestHostVersion() {
        super(FuncTest.appDesc);
    }

    public class TestModule extends AbstractModule {

        private final String basePath;

        public TestModule(String basePath) {
            this.basePath = basePath;
        }

        @Override
        protected void configure() {
            bind(PathBuilder.class).toInstance(new PathBuilder(basePath));
        }

        @Provides
        @Singleton
        public Directory provideDirectory() {
            Directory directory = StaticMockDirectory.getDirectoryInstance();
            return directory;
        }

        @Provides @Singleton
        public ZkManager provideZkManager(Directory directory) {
            return new ZkManager(directory, basePath);
        }

        @Provides @Singleton
        public HostZkManager provideHostZkManager(ZkManager zkManager,
                                                  PathBuilder paths,
                                                  Serializer serializer) {
            return new HostZkManager(zkManager, paths, serializer);
        }
    }

    @Before
    public void setUp() throws InterruptedException,
                               KeeperException,
                               StateAccessException {
        WebResource resource = resource();

        injector = Guice.createInjector(
                new VersionModule(),
                new SerializationModule(),
                new TestModule(ZK_ROOT_MIDOLMAN));
        resource().accept(VendorMediaType.APPLICATION_JSON_V5)
                .get(ClientResponse.class);
        hostManager = injector.getInstance(HostZkManager.class);
        URI baseUri = resource().getURI();
        api = new MidonetApi(baseUri.toString());
        api.enableLogging();
    }

    @Test
    public void testGet() throws StateAccessException {
        UUID myUuid = UUID.randomUUID();
        UUID anotherUuid = UUID.randomUUID();

        ResourceCollection<HostVersion> hostVersions = api.getHostVersions();
        assertThat("Hosts array should not be null", hostVersions, is(notNullValue()));
        assertThat("Hosts should be empty", hostVersions.size(), equalTo(0));

        hostManager.setHostVersion(myUuid);
        hostVersions = api.getHostVersions();

        assertThat("Hosts should be empty", hostVersions.size(), equalTo(1));
        assertThat("Host ID should not have changed",
                hostVersions.get(0).getHostId().equals(myUuid));
        assertThat("Host Version should not have changed",
                hostVersions.get(0).getVersion().equals(DataWriteVersion.CURRENT));

        hostManager.setHostVersion(anotherUuid);
        hostVersions = api.getHostVersions();

        assertThat("Hosts should be empty", hostVersions.size(), equalTo(2));
        assertThat("Host Versions should be the same",
                hostVersions.get(0).getVersion().equals(
                        hostVersions.get(1).getVersion()));
        assertThat("Host UUIDs should NOT be the same",
                !hostVersions.get(0).getHostId().equals(
                        hostVersions.get(1).getHostId()));
    }
}
