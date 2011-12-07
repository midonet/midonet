package com.midokura.midonet.smoketest.mocks;

import java.net.URI;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriBuilder;

import com.midokura.midolman.mgmt.rest_api.jaxrs.WildCardJacksonJaxbJsonProvider;
import org.codehaus.jackson.map.SerializationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.config.AppConfig;
import com.midokura.midonet.smoketest.mgmt.DtoAdRoute;
import com.midokura.midonet.smoketest.mgmt.DtoAdmin;
import com.midokura.midonet.smoketest.mgmt.DtoApplication;
import com.midokura.midonet.smoketest.mgmt.DtoBgp;
import com.midokura.midonet.smoketest.mgmt.DtoLogicalRouterPort;
import com.midokura.midonet.smoketest.mgmt.DtoMaterializedRouterPort;
import com.midokura.midonet.smoketest.mgmt.DtoPeerRouterLink;
import com.midokura.midonet.smoketest.mgmt.DtoRoute;
import com.midokura.midonet.smoketest.mgmt.DtoRouter;
import com.midokura.midonet.smoketest.mgmt.DtoRule;
import com.midokura.midonet.smoketest.mgmt.DtoRuleChain;
import com.midokura.midonet.smoketest.mgmt.DtoTenant;
import com.midokura.midonet.smoketest.mgmt.DtoVpn;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.core.ResourceConfig;
import com.sun.jersey.api.json.JSONConfiguration;
import com.sun.jersey.test.framework.JerseyTest;
import com.sun.jersey.test.framework.WebAppDescriptor;
import com.sun.jersey.test.framework.spi.container.TestContainerFactory;
import com.sun.jersey.test.framework.spi.container.grizzly2.web.GrizzlyWebTestContainerFactory;

public class MockMidolmanMgmt extends JerseyTest implements MidolmanMgmt {

    private final static Logger log = 
        LoggerFactory.getLogger(MockMidolmanMgmt.class);

    DtoApplication app;

    private static WebAppDescriptor makeAppDescriptor(boolean mockZK) {
        WebAppDescriptor ad = new WebAppDescriptor.Builder(
                "com.midokura.midolman.mgmt.rest_api.resources",
                "com.midokura.midolman.mgmt.rest_api.jaxrs",
                "com.midokura.midolman.mgmt.data")
                .initParam(JSONConfiguration.FEATURE_POJO_MAPPING, "true")
                .initParam(
                        "com.sun.jersey.spi.container.ContainerRequestFilters",
                        "com.midokura.midolman.mgmt.auth.NoAuthFilter")
                .contextParam("version", "1.0")
                .contextParam("datastore_service",
                                 mockZK
                                     ? "com.midokura.midolman.mgmt.data.MockDaoFactory"
                                     : "com.midokura.midolman.mgmt.data.zookeeper.ZooKeeperDaoFactory")
                .contextParam("zk_conn_string", "127.0.0.1:2181")
                .contextParam("zk_timeout", "10000")
                .contextParam("zk_root", "/test/midolman")
                .contextParam("zk_mgmt_root", "/test/midolman-mgmt")
                .contextPath("/test").build();
        ad.getClientConfig().getSingletons().add(new WildCardJacksonJaxbJsonProvider());

        return ad;
    }

    public MockMidolmanMgmt(boolean mockZK) {
        super(makeAppDescriptor(mockZK));
        // Initialize the directory structure.
        app = get("", DtoApplication.class);
        DtoAdmin admin = get(app.getAdmin(), DtoAdmin.class);
        post(admin.getInit(), null);
        // resource().path("admin/init").type(MediaType.APPLICATION_JSON)
        // .post(ClientResponse.class).getLocation();
    }

    public void stop() {
        log.info("Shutting down the WebApplication !");
        try {
            tearDown();
        } catch (Exception e) {
            log.error("While shutting down the mock manager:", e);
        }
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
        return resource().uri(uri).type(MediaType.APPLICATION_JSON)
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
    public DtoPeerRouterLink linkRouterToPeer(DtoRouter router,
            DtoLogicalRouterPort logPort) {
        return resource().uri(router.getPeerRouters())
                .type(MediaType.APPLICATION_JSON)
                .post(DtoPeerRouterLink.class, logPort);
        // URI uri = post(router.getPeerRouters(), logPort);
        // return get(uri, DtoPeerRouterLink.class);
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

    @Override
    public DtoBgp addBGP(DtoMaterializedRouterPort p, DtoBgp b) {
        URI uri = post(p.getBgps(), b);
        return get(uri, DtoBgp.class);
    }

    @Override
    public DtoAdRoute addBgpAdvertisedRoute(DtoBgp bgp, DtoAdRoute adRoute) {
        URI uri = post(bgp.getAdRoutes(), adRoute);
        return get(uri, DtoAdRoute.class);
    }

    public DtoTenant[] getTenants() {
        return get(app.getTenant(), DtoTenant[].class);
    }

    @Override
    public void deleteTenant(String name) {
        delete(UriBuilder.fromUri(app.getTenant()).path(name).build());
    }

    @Override
    public DtoRuleChain addRuleChain(DtoRouter router, DtoRuleChain chain) {
        URI uri = post(router.getChains(), chain);
        return get(uri, DtoRuleChain.class);
    }

    @Override
    public DtoRuleChain getRuleChain(DtoRouter router, String name) {
        URI uri = UriBuilder.fromUri(router.getUri())
                .path("/tables/nat/chains/" + name).build();
        return get(uri, DtoRuleChain.class);
    }

    @Override
    public DtoRule addRule(DtoRuleChain chain, DtoRule rule) {
        URI uri = post(chain.getRules(), rule);
        // TODO(pino): return the entity from GET
        //return get(uri, DtoRule.class);
        return rule;
    }

    @Override
    public DtoVpn addVpn(DtoMaterializedRouterPort p, DtoVpn vpn) {
        URI uri = post(p.getVpns(), vpn);
        return get(uri, DtoVpn.class);
    }

    @Override
    public DtoRoute[] getRoutes(DtoRouter router) {
        return get(router.getRoutes(), DtoRoute[].class);
    }
}
