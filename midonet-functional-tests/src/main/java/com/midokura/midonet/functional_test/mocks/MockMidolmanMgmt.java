package com.midokura.midonet.functional_test.mocks;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriBuilder;

import com.google.inject.servlet.GuiceFilter;
import com.midokura.midolman.mgmt.servlet.JerseyGuiceServletContextListener;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.test.framework.JerseyTest;
import com.sun.jersey.test.framework.WebAppDescriptor;
import com.sun.jersey.test.framework.spi.container.TestContainerFactory;
import com.sun.jersey.test.framework.spi.container.grizzly2.web.GrizzlyWebTestContainerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dto.client.DtoAdRoute;
import com.midokura.midolman.mgmt.data.dto.client.DtoApplication;
import com.midokura.midolman.mgmt.data.dto.client.DtoBgp;
import com.midokura.midolman.mgmt.data.dto.client.DtoBridge;
import com.midokura.midolman.mgmt.data.dto.client.DtoBridgePort;
import com.midokura.midolman.mgmt.data.dto.client.DtoDhcpHost;
import com.midokura.midolman.mgmt.data.dto.client.DtoDhcpSubnet;
import com.midokura.midolman.mgmt.data.dto.client.DtoHost;
import com.midokura.midolman.mgmt.data.dto.client.DtoInterface;
import com.midokura.midolman.mgmt.data.dto.client.DtoLogicalBridgePort;
import com.midokura.midolman.mgmt.data.dto.client.DtoLogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.client.DtoMaterializedRouterPort;
import com.midokura.midolman.mgmt.data.dto.client.DtoPort;
import com.midokura.midolman.mgmt.data.dto.client.DtoPortGroup;
import com.midokura.midolman.mgmt.data.dto.client.DtoRoute;
import com.midokura.midolman.mgmt.data.dto.client.DtoRouter;
import com.midokura.midolman.mgmt.data.dto.client.DtoRule;
import com.midokura.midolman.mgmt.data.dto.client.DtoRuleChain;
import com.midokura.midolman.mgmt.data.dto.client.DtoVpn;
import com.midokura.midolman.mgmt.jaxrs.WildCardJacksonJaxbJsonProvider;
import com.midokura.midolman.mgmt.http.VendorMediaType;

public class MockMidolmanMgmt extends JerseyTest implements MidolmanMgmt {

    private final static Logger log =
        LoggerFactory.getLogger(MockMidolmanMgmt.class);

    DtoApplication app;
    private static AtomicInteger portSeed = new AtomicInteger(3181);
    private int currentPort;

    public static WebAppDescriptor.Builder getAppDescriptorBuilder(boolean mockZK) {
        ClientConfig clientConfig = new DefaultClientConfig();
        clientConfig.getSingletons().add(new WildCardJacksonJaxbJsonProvider());

        return new WebAppDescriptor.Builder()
                .contextListenerClass(JerseyGuiceServletContextListener.class)
                .filterClass(GuiceFilter.class)
                .servletPath("/")
                .contextParam("rest_api-version", "1")
                .contextParam("cors-access_control_allow_origin", "*")
                .contextParam("cors-access_control_allow_headers",
                        "Origin, X-Auth-Token, Content-Type, Accept")
                .contextParam("cors-access_control_allow_methods",
                        "GET, POST, PUT, DELETE, OPTIONS")
                .contextParam("cors-access_control-expose_headers",
                        "Location")
                .contextParam("auth-use_mock", "true")
                .contextParam("zookeeper-midolman_root_key",
                        "/smoketest/midonet")
                .contextParam("zookeeper-zookeeper_hosts", "127.0.0.1:2181")
                .contextParam("zookeeper-session_timeout", "10000")
                .contextParam("zookeeper-use_mock", mockZK ? "true" : "false")
                .contextPath("/test")
                .clientConfig(clientConfig);
    }

    public MockMidolmanMgmt(boolean mockZK) {
        this(getAppDescriptorBuilder(mockZK).build());
    }

    public MockMidolmanMgmt(WebAppDescriptor webAppDescriptor) {
        super(webAppDescriptor);
        app = get("", DtoApplication.class);
    }

    protected static int _getPort() {
        return portSeed.getAndAdd(1);
    }

    @Override
    protected synchronized int getPort(int defaultPort) {

        if (currentPort == 0) {
            currentPort = _getPort();
        }

        return currentPort;
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
        return post(uri, entity, MediaType.APPLICATION_JSON);
    }

    private URI post(URI uri, Object entity, String mediaType) {
        ClientResponse response = resource()
            .uri(uri)
            .type(mediaType)
            .post(ClientResponse.class, entity);

        if (response.getLocation() == null) {
            throw
                new IllegalStateException(
                    "A POST call to " + uri + " failed to return a proper " +
                        "location header: " + response + "\n" +
                        response.getEntity(String.class));
        }

        return response.getLocation();
    }


    private URI put(URI uri, Object entity) {
        return put(uri, entity, MediaType.APPLICATION_JSON);
    }

    private URI put(URI uri, Object entity, String mediaType) {
        ClientResponse response = resource()
            .uri(uri)
            .type(mediaType)
            .put(ClientResponse.class, entity);

        if (response.getStatus() != 204 && response.getStatus() != 200) {
            throw
                new IllegalStateException(
                    "A PUT call to " + uri + " failed to return response " +
                            "status of 200 OK or 204 NO CONTENT: " +
                            response + "\n" +
                            response.getEntity(String.class));
        }

        return response.getLocation();
    }

    @Override
    public <T> T get(String path, Class<T> clazz) {
        return makeResource(path).type(MediaType.APPLICATION_JSON).get(clazz);
    }

    public <T> T get(URI uri, Class<T> clazz) {
        if (uri == null)
            throw new IllegalArgumentException(
                "The URI can't be null. This usually means that a previous call " +
                    "to Mgmt REST api failed.");

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
        URI uri = post(app.getTenants(), t);
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
    public void updateBridge(DtoBridge b) {
        put(b.getUri(), b);
    }

    @Override
    public void updateRouter(DtoRouter r) {
        put(r.getUri(), r);
    }

    @Override
    public void updatePort(DtoPort p) {
        put(p.getUri(), p);
    }

    @Override
    public void linkRouterToPeer(DtoLogicalRouterPort port) {
        resource().uri(port.getLink())
            .type(MediaType.APPLICATION_JSON)
            .post(port);
    }

    @Override
    public DtoMaterializedRouterPort addMaterializedRouterPort(DtoRouter r,
                                                   DtoMaterializedRouterPort p) {
        URI uri = post(r.getPorts(), p);
        return get(uri, DtoMaterializedRouterPort.class);
    }

    @Override
    public DtoLogicalRouterPort addLogicalRouterPort(DtoRouter r,
            DtoLogicalRouterPort p) {
        URI uri = post(r.getPorts(), p);
        return get(uri, DtoLogicalRouterPort.class);
    }

    @Override
    public DtoBridgePort addMaterializedBridgePort(DtoBridge b,
                                 DtoBridgePort p) {
        URI uri = post(b.getPorts(), p);
        return get(uri, DtoBridgePort.class);
    }

    @Override
    public DtoLogicalBridgePort addLogicalBridgePort(DtoBridge b,
            DtoLogicalBridgePort p) {
        URI uri = post(b.getPorts(), p);
        return get(uri, DtoLogicalBridgePort.class);
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
        return get(app.getTenants(), DtoTenant[].class);
    }

    public DtoHost[] getHosts() {
        ClientResponse clientResponse = get(app.getHosts(),
                                            ClientResponse.class);
        if (clientResponse.getClientResponseStatus() == ClientResponse.Status.OK) {
            return clientResponse.getEntity(DtoHost[].class);
        }

        return new DtoHost[0];
    }

    @Override
    public DtoHost getHost(URI uri) {
        return get(uri, DtoHost.class);
    }

    @Override
    public DtoInterface[] getHostInterfaces(DtoHost host) {
        ClientResponse clientResponse = get(host.getInterfaces(),
                                            ClientResponse.class);
        if (clientResponse.getClientResponseStatus() == ClientResponse.Status.OK) {
            return clientResponse.getEntity(DtoInterface[].class);
        }

        return new DtoInterface[0];
    }

    @Override
    public DtoInterface getHostInterface(DtoInterface dtoInterface) {
        return
            resource()
                .uri(dtoInterface.getUri())
                .type(VendorMediaType.APPLICATION_INTERFACE_JSON)
                .get(DtoInterface.class);
    }

    @Override
    public void addInterface(DtoHost host, DtoInterface dtoInterface) {
        URI returnURI = post(host.getInterfaces(), dtoInterface);
        // TODO: return the HostCommand object
        int a = 10;
    }

    @Override
    public void updateInterface(DtoInterface dtoInterface) {
        URI returnURI = put(dtoInterface.getUri(), dtoInterface);
        // TODO: return the HostCommand object
        int a = 10;
    }

    @Override
    public DtoPortGroup addPortGroup(DtoTenant tenant, DtoPortGroup group) {
        URI uri = post(tenant.getPortGroups(), group,
                VendorMediaType.APPLICATION_PORTGROUP_JSON);
        return get(uri, DtoPortGroup.class);
    }

    @Override
    public void deleteTenant(String name) {
        delete(UriBuilder.fromUri(app.getTenants()).path(name).build());
    }

    @Override
    public DtoRuleChain addRuleChain(DtoTenant t, DtoRuleChain chain) {
        URI uri = post(t.getChains(), chain);
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
        return get(uri, DtoRule.class);
    }

    @Override
    public DtoVpn addVpn(DtoMaterializedRouterPort p, DtoVpn vpn) {
        URI uri = post(p.getVpns(), vpn);
        return get(uri, DtoVpn.class);
    }

    @Override
    public DtoDhcpSubnet addDhcpSubnet(DtoBridge dtoBridge,
                                       DtoDhcpSubnet dhcpSubnet) {
        URI uri = post(dtoBridge.getDhcpSubnets(), dhcpSubnet);
        return get(uri, DtoDhcpSubnet.class);
    }

    @Override
    public DtoDhcpHost addDhcpSubnetHost(DtoDhcpSubnet dtoSubnet,
                                         DtoDhcpHost host) {
        URI uri = post(dtoSubnet.getHosts(), host);
        return get(uri, DtoDhcpHost.class);
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
