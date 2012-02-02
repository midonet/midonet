package com.midokura.midonet.smoketest.mocks;

import com.midokura.midolman.mgmt.rest_api.jaxrs.WildCardJacksonJaxbJsonProvider;
import com.midokura.midonet.smoketest.mgmt.*;
import com.midokura.midonet.smoketest.utils.MidolmanLauncher;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.api.json.JSONConfiguration;
import com.sun.jersey.test.framework.JerseyTest;
import com.sun.jersey.test.framework.WebAppDescriptor;
import com.sun.jersey.test.framework.spi.container.TestContainerFactory;
import com.sun.jersey.test.framework.spi.container.grizzly2.web.GrizzlyWebTestContainerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriBuilder;

import java.io.IOException;
import java.net.URI;

public class MockMidolmanMgmt extends JerseyTest implements MidolmanMgmt {

    private final static Logger log =
        LoggerFactory.getLogger(MockMidolmanMgmt.class);

    DtoApplication app;
    MidolmanLauncher launcher;

    private static WebAppDescriptor makeAppDescriptor(boolean mockZK) {
        ClientConfig config = new DefaultClientConfig();
        config.getSingletons().add(new WildCardJacksonJaxbJsonProvider());
        WebAppDescriptor ad = new WebAppDescriptor.Builder()
                .initParam(JSONConfiguration.FEATURE_POJO_MAPPING, "true")
                .initParam(
                        "com.sun.jersey.spi.container.ContainerRequestFilters",
                        "com.midokura.midolman.mgmt.auth.NoAuthFilter")
                .initParam("javax.ws.rs.Application",
                        "com.midokura.midolman.mgmt.rest_api.RestApplication")
                .contextParam("authorizer",
                        "com.midokura.midolman.mgmt.auth.SimpleAuthorizer")
                .contextParam("version", "1.0")
                .contextParam("datastore_service",
                                 mockZK
                                     ? "com.midokura.midolman.mgmt.data.MockDaoFactory"
                                     : "com.midokura.midolman.mgmt.data.zookeeper.ZooKeeperDaoFactory")
                .contextParam("zk_conn_string", "127.0.0.1:2181")
                .contextParam("zk_timeout", "10000")
                .contextParam("zk_root", "/smoketest/midolman")
                .contextParam("zk_mgmt_root", "/smoketest/midolman-mgmt")
                .contextPath("/test").clientConfig(config).build();
        ad.getClientConfig().getSingletons().add(new WildCardJacksonJaxbJsonProvider());
        return ad;
    }

    public MockMidolmanMgmt(boolean mockZK) {
        super(makeAppDescriptor(mockZK));
        // Initialize the directory structure.
        app = get("", DtoApplication.class);
        // Start Midolmanj controllers
        try {
            launcher = new MidolmanLauncher();
        } catch (IOException e) {
            log.error("Error starting Midolmanj controllers: {}", e);
        }
    }

    public void stop() {
        log.info("Shutting down the WebApplication !");
        try {
            launcher.stop();
        } catch (Exception e) {
            log.error("While shutting down the mock manager:", e);
        }
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
    public DtoBridge addBridge(DtoTenant t, DtoBridge b) {
        URI uri = post(t.getBridges(), b);
        return get(uri, DtoBridge.class);
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
    public DtoPort addBridgePort(DtoBridge b,
                                       DtoPort p) {
        URI uri = post(b.getPorts(), p);
        return get(uri, DtoPort.class);
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
                .path("/tables/NAT/chains/" + name).build();
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
    public void deleteVpn(DtoVpn vpn) {
        delete(vpn.getUri());
    }

    @Override
    public DtoRoute[] getRoutes(DtoRouter router) {
        return get(router.getRoutes(), DtoRoute[].class);
    }
}
