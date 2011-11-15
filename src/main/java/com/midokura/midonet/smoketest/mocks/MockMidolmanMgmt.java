package com.midokura.midonet.smoketest.mocks;

import java.net.URI;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.config.AppConfig;
import com.midokura.midolman.mgmt.data.dto.MaterializedRouterPort;
import com.midokura.midolman.mgmt.data.dto.Route;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.data.dto.Tenant;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.json.JSONConfiguration;
import com.sun.jersey.test.framework.JerseyTest;
import com.sun.jersey.test.framework.WebAppDescriptor;
import com.sun.jersey.test.framework.spi.container.TestContainerFactory;
import com.sun.jersey.test.framework.spi.container.grizzly2.web.GrizzlyWebTestContainerFactory;

public class MockMidolmanMgmt extends JerseyTest implements MidolmanMgmt {

    private final static Logger log = LoggerFactory
            .getLogger(MockMidolmanMgmt.class);

    // TODO: make mocking ZK configurable.
    public MockMidolmanMgmt() {
        super(new WebAppDescriptor.Builder(new String[] {
                "com.midokura.midolman.mgmt.rest_api.resources",
                "com.midokura.midolman.mgmt.data" })
                .initParam(JSONConfiguration.FEATURE_POJO_MAPPING, "true")
                .initParam(
                        "com.sun.jersey.spi.container.ContainerRequestFilters",
                        "com.midokura.midolman.mgmt.auth.NoAuthFilter")
                .contextParam("datastore_service",
                        "com.midokura.midolman.mgmt.data.zookeeper.ZooKeeperDaoFactory")
                .contextParam("zk_conn_string", "127.0.0.1:2181")
                .contextParam("zk_timeout", "10000")
                .contextParam("zk_root", "/test/midolman")
                .contextParam("zk_mgmt_root", "/test/midolman-mgmt")
                .contextListenerClass(ServletListener.class)
                .contextPath("/test").build());
        // Initialize the directory structure.
        post("admin/init", null);
    }

    private WebResource makeResource(String path) {
        WebResource resource = resource();
        UriBuilder b = resource.getUriBuilder();
        if (path.startsWith("/")) {
            b.replacePath(path);
        } else {
            b.path(path);
        }
        URI uri = b.build();
        return resource.uri(uri);
    }

    private URI post(String path, Object entity) {
        return makeResource(path).type(MediaType.APPLICATION_JSON)
                .post(ClientResponse.class, entity).getLocation();
    }

    private URI post(URI uri, String path, Object entity) {
        return resource().uri(UriBuilder.fromUri(uri).path(path).build())
                .type(MediaType.APPLICATION_JSON)
                .post(ClientResponse.class, entity).getLocation();
    }

    @Override
    public <T> T get(String path, Class<T> clazz) {
        return makeResource(path).type(MediaType.APPLICATION_JSON).get(clazz);
    }

    @Override
    public void delete(String path) {
        makeResource(path).type(MediaType.APPLICATION_JSON).delete();
    }

    public static class ServletListener implements ServletContextListener {

        @Override
        public void contextDestroyed(ServletContextEvent ctx) {
            // Do nothing
        }

        @Override
        public void contextInitialized(ServletContextEvent ctx) {
            AppConfig.init(ctx.getServletContext());
        }
    }

    @Override
    protected TestContainerFactory getTestContainerFactory() {
        return new GrizzlyWebTestContainerFactory();
    }

    @Override
    public URI addTenant(Tenant t) {
        return post("tenants", t);
    }

    @Override
    public URI addRouter(URI tenantURI, Router r) {
        return post(tenantURI, "routers", r);
    }

    @Override
    public URI addRouterPort(URI routerURI, MaterializedRouterPort p) {
        return post(routerURI, "ports", p);
    }

    @Override
    public URI addRoute(URI routerURI, Route rt) {
        return post(routerURI, "routes", rt);
    }

}
