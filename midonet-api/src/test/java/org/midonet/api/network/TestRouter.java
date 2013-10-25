/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network;

import com.google.inject.*;
import org.apache.zookeeper.KeeperException;
import org.codehaus.jackson.type.JavaType;
import org.midonet.api.serialization.SerializationModule;
import org.midonet.client.VendorMediaType;
import org.midonet.midolman.host.state.HostDirectory;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.serialization.Serializer;


import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.ArpCacheEntry;
import org.midonet.midolman.state.ArpTable;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.zkManagers.FiltersZkManager;
import org.midonet.midolman.state.zkManagers.RouterZkManager;
import org.midonet.api.rest_api.DtoWebResource;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.api.rest_api.Topology;
import org.midonet.api.zookeeper.StaticMockDirectory;
import org.midonet.client.dto.*;
import org.midonet.midolman.version.guice.VersionModule;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;
import junit.framework.Assert;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import javax.ws.rs.core.UriBuilder;
import java.net.URI;
import java.util.*;


import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertThat;
import static org.midonet.api.VendorMediaType.*;
import static javax.ws.rs.core.Response.Status.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(Enclosed.class)
public class TestRouter {

    public static class TestRouterList extends JerseyTest {

        private Topology topology;
        private DtoWebResource dtoResource;

        public TestRouterList() {
            super(FuncTest.appDesc);
        }

        private void addActualRouters(Topology.Builder builder, String tenantId,
                                      int count) {
            for (int i = 0 ; i < count ; i++) {
                DtoRouter router = new DtoRouter();
                String tag = Integer.toString(i) + tenantId;
                router.setName(tag);
                router.setTenantId(tenantId);
                builder.create(tag, router);
            }
        }

        private DtoRouter getExpectedRouter(URI routersUri, String tag) {

            DtoRouter r = topology.getRouter(tag);
            String uri = routersUri.toString() + "/" + r.getId();

            // Make sure you set the non-ID fields to values you expect
            r.setName(tag);
            r.setUri(UriBuilder.fromUri(uri).build());
            r.setPorts(UriBuilder.fromUri(uri + "/ports").build());
            r.setPeerPorts(UriBuilder.fromUri(uri + "/peer_ports").build());
            r.setRoutes(UriBuilder.fromUri(uri + "/routes").build());

            return r;
        }

        private List<DtoRouter> getExpectedRouters(URI routersUri,
                                                   String tenantId,
                                                   int startTagNum,
                                                   int endTagNum) {
            List<DtoRouter> routers = new ArrayList<DtoRouter>();

            for (int i = startTagNum; i <= endTagNum; i++) {
                String tag = Integer.toString(i) + tenantId;
                DtoRouter r = getExpectedRouter(routersUri, tag);
                routers.add(r);
            }

            return routers;
        }

        @Before
        public void setUp() {

            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            Topology.Builder builder = new Topology.Builder(dtoResource);

            // Create 5 routers for tenant 0
            addActualRouters(builder, "tenant0", 5);

            // Create 5 routers for tenant 1
            addActualRouters(builder, "tenant1", 5);

            topology = builder.build();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Test
        public void testListAllRouters() throws Exception {

            // Get the expected list of DtoBridge objects
            DtoApplication app = topology.getApplication();
            List<DtoRouter> expected = getExpectedRouters(app.getRouters(),
                    "tenant0", 0, 4);
            expected.addAll(getExpectedRouters(
                    app.getRouters(),"tenant1", 0, 4));

            // Get the actual DtoRouter objects
            String actualRaw = dtoResource.getAndVerifyOk(app.getRouters(),
                    VendorMediaType.APPLICATION_ROUTER_COLLECTION_JSON,
                    String.class);
            JavaType type = FuncTest.objectMapper.getTypeFactory()
                    .constructParametricType(List.class, DtoRouter.class);
            List<DtoRouter> actual = FuncTest.objectMapper.readValue(
                    actualRaw, type);

            // Compare the actual and expected
            assertThat(actual, hasSize(expected.size()));
            assertThat(actual, containsInAnyOrder(expected.toArray()));
        }

        @Test
        public void testListRoutersPerTenant() throws Exception {

            // Get the expected list of DtoBridge objects
            DtoApplication app = topology.getApplication();
            DtoTenant tenant = topology.getTenant("tenant0");
            List<DtoRouter> expected = getExpectedRouters(app.getRouters(),
                    tenant.getId(), 0, 4);

            // Get the actual DtoRouter objects
            String actualRaw = dtoResource.getAndVerifyOk(tenant.getRouters(),
                    VendorMediaType.APPLICATION_ROUTER_COLLECTION_JSON,
                    String.class);
            JavaType type = FuncTest.objectMapper.getTypeFactory()
                    .constructParametricType(List.class, DtoRouter.class);
            List<DtoRouter> actual = FuncTest.objectMapper.readValue(
                    actualRaw, type);

            // Compare the actual and expected
            assertThat(actual, hasSize(expected.size()));
            assertThat(actual, containsInAnyOrder(expected.toArray()));
        }
    }

    public static class TestRouterCrud extends JerseyTest {

        private DtoWebResource dtoResource;
        private Topology topology;
        private Directory dir;
        private Injector injector = null;

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
            public Directory provideDirectory() {
                Directory directory
                        = StaticMockDirectory.getDirectoryInstance();
                return directory;
            }

            @Provides @Singleton
            public ZkManager provideZkManager(Directory directory) {
                return new ZkManager(directory);
            }

            @Provides @Singleton
            public RouterZkManager provideRouterZkManager(ZkManager zkManager,
                                                      PathBuilder paths,
                                                      Serializer serializer) {
                return new RouterZkManager(zkManager, paths, serializer);
            }

            @Provides @Singleton
            public HostZkManager provideHostZkManager(ZkManager zkManager,
                                                      PathBuilder paths,
                                                      Serializer serializer) {
                return new HostZkManager(zkManager, paths, serializer);
            }

            @Provides @Singleton
            public FiltersZkManager provideFilterZkManager(ZkManager zkManager,
                                                      PathBuilder paths,
                                                      Serializer serializer) {
                return new FiltersZkManager(zkManager, paths, serializer);
            }
        }

        public TestRouterCrud() {
            super(FuncTest.appDesc);
        }

        private Map<String, String> getTenantQueryParams(String tenantId) {
            Map<String, String> queryParams = new HashMap<String, String>();
            queryParams.put("tenant_id", tenantId);
            return queryParams;
        }

        @Before
        public void setUp() throws KeeperException, InterruptedException {
            String basePath = "/test/midolman";
            injector = Guice.createInjector(
                    new VersionModule(),
                    new SerializationModule(),
                    new TestModule(basePath));
            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            // Prepare chains that can be used to set to port
            DtoRuleChain chain1 = new DtoRuleChain();
            chain1.setName("chain1");
            chain1.setTenantId("tenant1-id");

            // Prepare another chain
            DtoRuleChain chain2 = new DtoRuleChain();
            chain2.setName("chain2");
            chain2.setTenantId("tenant1-id");

            topology = new Topology.Builder(dtoResource)
                    .create("chain1", chain1)
                    .create("chain2", chain2).build();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        private DtoRouter createRouter(
                String name, String tenant, boolean withChains) {
            DtoApplication app = topology.getApplication();
            DtoRuleChain chain1 = topology.getChain("chain1");
            DtoRuleChain chain2 = topology.getChain("chain2");

            DtoRouter router = new DtoRouter();
            router.setName(name);
            router.setTenantId(tenant);
            if (withChains) {
                router.setInboundFilterId(chain1.getId());
                router.setOutboundFilterId(chain2.getId());
            }

            DtoRouter resRouter = dtoResource.postAndVerifyCreated(
                app.getRouters(), APPLICATION_ROUTER_JSON, router,
                DtoRouter.class);
            assertNotNull(resRouter.getId());
            assertNotNull(resRouter.getUri());
            // TODO: Implement 'equals' for DtoRouter
            assertEquals(router.getTenantId(), resRouter.getTenantId());
            if (withChains) {
                assertEquals(router.getInboundFilterId(),
                    resRouter.getInboundFilterId());
                assertEquals(router.getOutboundFilterId(),
                    resRouter.getOutboundFilterId());
            }
            return resRouter;
        }

        @Test
        public void testCrud() throws Exception {
            DtoApplication app = topology.getApplication();
            DtoRuleChain chain1 = topology.getChain("chain1");
            DtoRuleChain chain2 = topology.getChain("chain2");

            assertNotNull(app.getRouters());
            DtoRouter[] routers = dtoResource.getAndVerifyOk(app.getRouters(),
                    getTenantQueryParams("tenant1-id"),
                    APPLICATION_ROUTER_COLLECTION_JSON, DtoRouter[].class);
            assertEquals(0, routers.length);

            // Add a router
            DtoRouter resRouter = createRouter("router1", "tenant1-id", true);

            // List the routers
            routers = dtoResource.getAndVerifyOk(app.getRouters(),
                    getTenantQueryParams("tenant1-id"),
                    APPLICATION_ROUTER_COLLECTION_JSON, DtoRouter[].class);
            assertEquals(1, routers.length);
            assertEquals(resRouter.getId(), routers[0].getId());

            // Update the router: change name, admin state, and swap filters.
            resRouter.setName("router1-modified");
            resRouter.setAdminStateUp(false);
            resRouter.setInboundFilterId(chain2.getId());
            resRouter.setOutboundFilterId(chain1.getId());
            DtoRouter updatedRouter = dtoResource.putAndVerifyNoContent(
                    resRouter.getUri(), APPLICATION_ROUTER_JSON, resRouter,
                    DtoRouter.class);
            assertNotNull(updatedRouter.getId());
            assertEquals(resRouter.getTenantId(), updatedRouter.getTenantId());
            assertEquals(resRouter.getInboundFilterId(),
                    updatedRouter.getInboundFilterId());
            assertEquals(resRouter.getOutboundFilterId(),
                    updatedRouter.getOutboundFilterId());
            assertEquals(resRouter.getName(), updatedRouter.getName());
            assertEquals(resRouter.isAdminStateUp(),
                         updatedRouter.isAdminStateUp());

            // Delete the router
            dtoResource.deleteAndVerifyNoContent(resRouter.getUri(),
                APPLICATION_ROUTER_JSON);

            // Verify that it's gone
            dtoResource.getAndVerifyNotFound(resRouter.getUri(),
                APPLICATION_ROUTER_JSON);

            // List should return an empty array
            routers = dtoResource.getAndVerifyOk(app.getRouters(),
                    getTenantQueryParams("tenant1-id"),
                    APPLICATION_ROUTER_COLLECTION_JSON, DtoRouter[].class);
            assertEquals(0, routers.length);
        }

        @Test
        public void testRouterDeleteWithArpEntries() throws Exception {
            // Add a router
            DtoRouter resRouter = createRouter("router1", "tenant1-id", false);
            // Add an ARP entry in this router's ARP cache.
            RouterZkManager routerMgr
                    = injector.getInstance(RouterZkManager.class);
            ArpTable arpTable =
                new ArpTable(routerMgr.getArpTableDirectory(resRouter.getId()));
            arpTable.put(IPv4Addr.fromString("10.0.0.3"),
                new ArpCacheEntry(MAC.fromString("02:00:dd:ee:ee:55"),
                    1000, 1000, 3000));
            dtoResource.deleteAndVerifyNoContent(
                resRouter.getUri(), APPLICATION_ROUTER_JSON);
        }

        @Test
        public void testRouterDeleteWithSnatBlockLeases() throws Exception {
            // Add a router
            DtoRouter resRouter = createRouter("router1", "tenant1-id", false);
            // Reserve a SNAT block in this router.
            FiltersZkManager filtersMgr
                    = injector.getInstance(FiltersZkManager.class);
            filtersMgr.addSnatReservation(resRouter.getId(),
                                 new IPv4Addr(0x0a000001), 100);
            dtoResource.deleteAndVerifyNoContent(
                resRouter.getUri(), APPLICATION_ROUTER_JSON);
        }

        @Test
        public void testRouterDeleteWithBoundExteriorPort() throws Exception {
            // Add a router
            DtoRouter resRouter = createRouter("router1", "tenant1-id", false);
            // Add an exterior port.
            DtoRouterPort port = new DtoRouterPort();
            port.setNetworkAddress("10.0.0.0");
            port.setNetworkLength(24);
            port.setPortAddress("10.0.0.1");
            DtoRouterPort resPort =
                dtoResource.postAndVerifyCreated(resRouter.getPorts(),
                    APPLICATION_PORT_V2_JSON, port, DtoRouterPort.class);
            // Create a host (this is not allowed via the API).
            HostZkManager hostManager = injector.getInstance(HostZkManager.class);
            HostDirectory.Metadata metadata = new HostDirectory.Metadata();
            metadata.setName("semporiki");
            UUID hostId = UUID.randomUUID();
            hostManager.createHost(hostId, metadata);
            // Get the host DTO.
            DtoHost[] hosts = dtoResource.getAndVerifyOk(
                topology.getApplication().getHosts(),
                APPLICATION_HOST_COLLECTION_JSON, DtoHost[].class);
            Assert.assertEquals(1, hosts.length);
            DtoHost resHost = hosts[0];
            // Bind the exterior port to an interface on the host.
            DtoHostInterfacePort hostBinding = new DtoHostInterfacePort();
            hostBinding.setHostId(resHost.getId());
            hostBinding.setInterfaceName("eth0");
            hostBinding.setPortId(resPort.getId());
            DtoHostInterfacePort resPortBinding =
                dtoResource.postAndVerifyCreated(resHost.getPorts(),
                    APPLICATION_HOST_INTERFACE_PORT_JSON, hostBinding,
                    DtoHostInterfacePort.class);
            // Deleting the router is not allowed because of the binding.
            ClientResponse resp = dtoResource.deleteAndVerifyStatus(
                resRouter.getUri(), APPLICATION_JSON, CONFLICT.getStatusCode());
            // Unbind the port. Deleting the router now succeeds.
            dtoResource.deleteAndVerifyNoContent(resPortBinding.getUri(),
                APPLICATION_JSON);
            dtoResource.deleteAndVerifyNoContent(
                resRouter.getUri(), APPLICATION_ROUTER_JSON);
        }

        @Test
        public void testRouterDeleteWithLinkedInteriorPort() throws Exception {
            // Add a router
            DtoRouter resRouter = createRouter("router1", "tenant1-id", false);
            // Add an interior router port.
            DtoRouterPort port = new DtoRouterPort();
            port.setNetworkAddress("10.0.0.0");
            port.setNetworkLength(24);
            port.setPortAddress("10.0.0.1");
            DtoRouterPort resPort =
                dtoResource.postAndVerifyCreated(resRouter.getPorts(),
                    APPLICATION_PORT_V2_JSON, port, DtoRouterPort.class);
            assertNotNull(resPort.getId());
            // Create a bridge that we can link to the router.
            DtoBridge bridge = new DtoBridge();
            bridge.setName("bridge1");
            bridge.setTenantId("tenant1");
            DtoBridge resBridge = dtoResource.postAndVerifyCreated(
                topology.getApplication().getBridges(),
                APPLICATION_BRIDGE_JSON, bridge, DtoBridge.class);
            assertNotNull(resBridge.getId());
            assertNotNull(resBridge.getUri());
            // Add an interior bridge port.
            DtoBridgePort bPort = dtoResource.postAndVerifyCreated(
                resBridge.getPorts(), APPLICATION_PORT_V2_JSON,
                new DtoBridgePort(), DtoBridgePort.class);
            assertNotNull(bPort.getId());
            assertNotNull(bPort.getUri());
            // Link the bridge and router ports.
            bPort.setPeerId(resPort.getId());
            dtoResource.postAndVerifyStatus(bPort.getLink(),
                APPLICATION_PORT_LINK_JSON, bPort, CREATED.getStatusCode());
            // Deleting the router is not allowed.
            dtoResource.deleteAndVerifyStatus(
                resRouter.getUri(), APPLICATION_JSON, CONFLICT.getStatusCode());
            // Unlink the bridge and router. Now the router delete succeeds.
            dtoResource.deleteAndVerifyStatus(bPort.getLink(),
                APPLICATION_JSON, NO_CONTENT.getStatusCode());
            dtoResource.deleteAndVerifyNoContent(
                resRouter.getUri(), APPLICATION_ROUTER_JSON);
        }

    }

    @RunWith(Parameterized.class)
    public static class TestCreateRouterBadRequest extends JerseyTest {

        private Topology topology;
        private DtoWebResource dtoResource;
        private final DtoRouter router;
        private final String property;

        public TestCreateRouterBadRequest(DtoRouter router, String property) {
            super(FuncTest.appDesc);
            this.router = router;
            this.property = property;
        }

        @Before
        public void setUp() {

            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            // Create a router - useful for checking duplicate name error
            DtoRouter r = new DtoRouter();
            r.setName("router1-name");
            r.setTenantId("tenant1-id");

            topology = new Topology.Builder(dtoResource)
                    .create("router1", r).build();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Parameters
        public static Collection<Object[]> data() {

            List<Object[]> params = new ArrayList<Object[]>();

            // Null name
            DtoRouter nullNameRouter = new DtoRouter();
            nullNameRouter.setTenantId("tenant1-id");
            params.add(new Object[] { nullNameRouter, "name" });

            // Blank name
            DtoRouter blankNameRouter = new DtoRouter();
            blankNameRouter.setName("");
            blankNameRouter.setTenantId("tenant1-id");
            params.add(new Object[] { blankNameRouter, "name" });

            // Long name
            StringBuilder longName = new StringBuilder(
                    Router.MAX_ROUTER_NAME_LEN + 1);
            for (int i = 0; i < Router.MAX_ROUTER_NAME_LEN + 1; i++) {
                longName.append("a");
            }
            DtoRouter longNameRouter = new DtoRouter();
            longNameRouter.setName(longName.toString());
            longNameRouter.setTenantId("tenant1-id");
            params.add(new Object[] { longNameRouter, "name" });

            // Router name already exists
            DtoRouter dupNameRouter = new DtoRouter();
            dupNameRouter.setName("router1-name");
            dupNameRouter.setTenantId("tenant1-id");
            params.add(new Object[]{dupNameRouter, "name"});

            // Router with tenantID missing
            DtoRouter noTenant = new DtoRouter();
            noTenant.setName("noTenant-router-name");
            params.add(new Object[] { noTenant, "tenantId" });

            return params;
        }

        @Test
        public void testBadInputCreate() {
            DtoApplication app = topology.getApplication();
            DtoError error = dtoResource.postAndVerifyBadRequest(
                    app.getRouters(), APPLICATION_ROUTER_JSON, router);
            List<Map<String, String>> violations = error.getViolations();
            assertEquals(1, violations.size());
            assertEquals(property, violations.get(0).get("property"));
        }
    }

    @RunWith(Parameterized.class)
    public static class TestUpdateRouterBadRequest extends JerseyTest {

        private final DtoRouter testRouter;
        private final String property;
        private DtoWebResource dtoResource;
        private Topology topology;

        public TestUpdateRouterBadRequest(DtoRouter testRouter, String property) {
            super(FuncTest.appDesc);
            this.testRouter = testRouter;
            this.property = property;
        }

        @Before
        public void setUp() {

            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            // Create a router
            DtoRouter r1 = new DtoRouter();
            r1.setName("router1-name");
            r1.setTenantId("tenant1-id");

            // Create another router - useful for checking duplicate name error
            DtoRouter r2 = new DtoRouter();
            r2.setName("router2-name");
            r2.setTenantId("tenant1-id");

            topology = new Topology.Builder(dtoResource)
                    .create("router1", r1)
                    .create("router2", r2).build();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Parameters
        public static Collection<Object[]> data() {

            List<Object[]> params = new ArrayList<Object[]>();

            // Null name
            DtoRouter nullNameRouter = new DtoRouter();
            nullNameRouter.setTenantId("tenant1-id");
            params.add(new Object[] { nullNameRouter, "name" });

            // Blank name
            DtoRouter blankNameRouter = new DtoRouter();
            blankNameRouter.setName("");
            blankNameRouter.setTenantId("tenant1-id");
            params.add(new Object[] { blankNameRouter, "name" });

            // Long name
            StringBuilder longName = new StringBuilder(
                    Router.MAX_ROUTER_NAME_LEN + 1);
            for (int i = 0; i < Router.MAX_ROUTER_NAME_LEN + 1; i++) {
                longName.append("a");
            }
            DtoRouter longNameRouter = new DtoRouter();
            longNameRouter.setName(longName.toString());
            longNameRouter.setTenantId("tenant1-id");
            params.add(new Object[] { longNameRouter, "name" });

            // Router name already exists
            DtoRouter dupNameRouter = new DtoRouter();
            dupNameRouter.setName("router2-name");
            dupNameRouter.setTenantId("tenant1-id");
            params.add(new Object[] { dupNameRouter, "name" });

            // Router with tenantID missing
            DtoRouter noTenant = new DtoRouter();
            noTenant.setName("noTenant-router-name");
            params.add(new Object[] { noTenant, "tenantId" });

            return params;
        }

        @Test
        public void testBadInput() {
            // Get the router
            DtoRouter router = topology.getRouter("router1");

            DtoError error = dtoResource.putAndVerifyBadRequest(
                    router.getUri(), APPLICATION_ROUTER_JSON, testRouter);
            List<Map<String, String>> violations = error.getViolations();
            assertEquals(1, violations.size());
            assertEquals(property, violations.get(0).get("property"));
        }
    }
}
