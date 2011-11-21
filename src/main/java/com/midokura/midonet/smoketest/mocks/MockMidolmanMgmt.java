package com.midokura.midonet.smoketest.mocks;

import java.net.URI;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.config.AppConfig;
import com.midokura.midonet.smoketest.mgmt.DtoAdmin;
import com.midokura.midonet.smoketest.mgmt.DtoApplication;
import com.midokura.midonet.smoketest.mgmt.DtoMaterializedRouterPort;
import com.midokura.midonet.smoketest.mgmt.DtoRoute;
import com.midokura.midonet.smoketest.mgmt.DtoRouter;
import com.midokura.midonet.smoketest.mgmt.DtoTenant;
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

    DtoApplication app;

    public MockMidolmanMgmt(boolean mockZK) {
        super(new WebAppDescriptor.Builder(new String[] {
                "com.midokura.midolman.mgmt.rest_api.resources",
                "com.midokura.midolman.mgmt.data" })
                .initParam(JSONConfiguration.FEATURE_POJO_MAPPING, "true")
                .initParam(
                        "com.sun.jersey.spi.container.ContainerRequestFilters",
                        "com.midokura.midolman.mgmt.auth.NoAuthFilter")
                .contextParam("version", "1.0")
                .contextParam("datastore_service", mockZK ? 
                        "com.midokura.midolman.mgmt.data.MockDaoFactory" :
                        "com.midokura.midolman.mgmt.data.zookeeper.ZooKeeperDaoFactory")
                .contextParam("zk_conn_string", "127.0.0.1:2181")
                .contextParam("zk_timeout", "10000")
                .contextParam("zk_root", "/test/midolman")
                .contextParam("zk_mgmt_root", "/test/midolman-mgmt")
                .contextListenerClass(ServletListener.class)
                .contextPath("/test").build());
        // Initialize the directory structure.
        app = get("", DtoApplication.class);
        DtoAdmin admin = get(app.getAdmin(), DtoAdmin.class);
        post(admin.getInit(), null);
        //resource().path("admin/init").type(MediaType.APPLICATION_JSON)
        //        .post(ClientResponse.class).getLocation();
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

    private URI post(URI uri, Object entity) {
        return resource().uri(uri)
                .type(MediaType.APPLICATION_JSON)
                .post(ClientResponse.class, entity).getLocation();
    }

    @Override
    public <T> T get(String path, Class<T> clazz) {
        return makeResource(path).type(MediaType.APPLICATION_JSON).get(clazz);
    }

    public <T> T get(URI uri, Class<T> clazz) {
        return resource().uri(uri).type(MediaType.APPLICATION_JSON).get(clazz);
    }

    @Override
    public void delete(URI uri) {
        resource().uri(uri).type(MediaType.APPLICATION_JSON).delete();
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
    public DtoTenant addTenant(DtoTenant t) {
        URI uri = post(app.getTenant(), t);
        return get(uri, DtoTenant.class);
    }

    @Override
    public DtoRouter addRouter(DtoTenant t, DtoRouter r) {
        URI uri = post(t.getRouters(), r);
        return get(uri, DtoRouter.class);
    }

    @Override
    public DtoMaterializedRouterPort addRouterPort(DtoRouter r,
            DtoMaterializedRouterPort p) {
        URI uri = post(r.getPorts(), p);
        return get(uri, DtoMaterializedRouterPort.class);
    }

    @Override
    public DtoRoute addRoute(DtoRouter r, DtoRoute rt) {
        URI uri = post(r.getRoutes(), rt);
        return get(uri, DtoRoute.class);
    }

}
