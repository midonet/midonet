package com.midokura.midolman.mgmt.tools;

import java.util.UUID;

import javax.ws.rs.core.MediaType;

import org.codehaus.jackson.jaxrs.JacksonJaxbJsonProvider;

import com.midokura.midolman.mgmt.data.dto.AdRoute;
import com.midokura.midolman.mgmt.data.dto.Bgp;
import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.mgmt.data.dto.LogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.MaterializedRouterPort;
import com.midokura.midolman.mgmt.data.dto.PeerRouterLink;
import com.midokura.midolman.mgmt.data.dto.Route;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.data.dto.Rule;
import com.midokura.midolman.mgmt.data.dto.Tenant;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;

public class CreateZkTestConfig {

    static String basePath = "http://mido-iida-2.midokura.jp:8081/midolmanj-mgmt/v1";

    public static void main(String[] args) {
        WebResource resource;
        String url;
        ClientResponse response;

        ClientConfig cc = new DefaultClientConfig();
        cc.getSingletons().add(new JacksonJaxbJsonProvider());
        Client client = Client.create(cc);
        //client.addFilter(new LoggingFilter());

//        url = new StringBuilder(basePath).append("/admin/init").toString();
//        resource = client.resource(url);
//        response = resource.type(MediaType.APPLICATION_JSON)
//                .header("HTTP_X_AUTH_TOKEN", "999888777666")
//                .post(ClientResponse.class);

        // Add the tenant
        url = new StringBuilder(basePath).append("/tenants").toString();
        resource = client.resource(url);
        response = resource.type(MediaType.APPLICATION_JSON)
                .header("HTTP_X_AUTH_TOKEN", "999888777666")
                .post(ClientResponse.class, new Tenant());
        String tenantUrl = response.getLocation().toString();
        System.out.println(tenantUrl);

        // Add the 'Provider' router - would normally use special Tenant ID.
        url = new StringBuilder(tenantUrl).append(
                "/routers").toString();
        resource = client.resource(url);
        Router rtr = new Router();
        rtr.setName("ProviderRouter");
        response = resource.type(MediaType.APPLICATION_JSON)
                .header("HTTP_X_AUTH_TOKEN", "999888777666")
                .post(ClientResponse.class, rtr);
        String providerRouterUrl = response.getLocation().toString();
        System.out.println(providerRouterUrl);
        String[] parts = providerRouterUrl.split("/");

        // Add the BGP port.
        url = new StringBuilder(providerRouterUrl).append("/ports").toString();
        resource = client.resource(url);
        MaterializedRouterPort materPort = new MaterializedRouterPort();
        materPort.setNetworkAddress("180.214.47.64");
        materPort.setNetworkLength(30);
        materPort.setPortAddress("180.214.47.66");
        materPort.setLocalNetworkAddress("180.214.47.64");
        materPort.setLocalNetworkLength(30);
        response = resource.type(MediaType.APPLICATION_JSON)
                .header("HTTP_X_AUTH_TOKEN", "999888777666")
                .post(ClientResponse.class, materPort);
        String portPath = response.getLocation().toString();
        parts = portPath.split("/");
        String bgpPortId = parts[parts.length - 1];
        System.out.println("BGP port id: " + bgpPortId);

        // Add a BGP to the bgp port.
        url = new StringBuilder(portPath).append(
                "/bgps").toString();
        resource = client.resource(url);
        Bgp bgp = new Bgp();
        bgp.setLocalAS(65104);
        bgp.setPeerAS(23637);
        bgp.setPeerAddr("180.214.47.65");
        response = resource.type(MediaType.APPLICATION_JSON)
                .header("HTTP_X_AUTH_TOKEN", "999888777666")
                .post(ClientResponse.class, bgp);

        // Advertise a route from the BGP.
        url = new StringBuilder(response.getLocation().toString()).append(
                "/ad_routes").toString();
        resource = client.resource(url);
        AdRoute adRt = new AdRoute();
        adRt.setNwPrefix("14.128.23.0");
        adRt.setPrefixLength((byte) 27);
        response = resource.type(MediaType.APPLICATION_JSON)
                .header("HTTP_X_AUTH_TOKEN", "999888777666")
                .post(ClientResponse.class, adRt);

        // Create a port for 14.128.23.4/30 directly on the provider router.
        url = new StringBuilder(providerRouterUrl).append("/ports").toString();
        resource = client.resource(url);
        materPort.setNetworkAddress("14.128.23.4");
        materPort.setNetworkLength(30);
        materPort.setPortAddress("14.128.23.5");
        materPort.setLocalNetworkAddress("14.128.23.4");
        materPort.setLocalNetworkLength(30);
        response = resource.type(MediaType.APPLICATION_JSON)
                .header("HTTP_X_AUTH_TOKEN", "999888777666")
                .post(ClientResponse.class, materPort);
        portPath = response.getLocation().toString();
        parts = portPath.split("/");
        String portId = parts[parts.length - 1];
        System.out.println("Port for 14.128.23.4/30 has id: " + portId);

        // Add a route to 14.128.23.4/30
        url = new StringBuilder(providerRouterUrl).append("/routes").toString();
        resource = client.resource(url);
        Route rt = new Route();
        rt.setSrcNetworkAddr("0.0.0.0");
        rt.setSrcNetworkLength(0);
        rt.setDstNetworkAddr("14.128.23.4");
        rt.setDstNetworkLength(30);
        rt.setType(Route.Normal);
        rt.setNextHopPort(UUID.fromString(portId));
        rt.setWeight(10);
        response = resource.type(MediaType.APPLICATION_JSON)
                .header("HTTP_X_AUTH_TOKEN", "999888777666")
                .post(ClientResponse.class, rt);

        // Create a low-priority route on the Provider that Rejects everything
        // to 14.128.23.0/27. This is to avoid sending this prefix back to
        // the uplink (default route).
        url = new StringBuilder(providerRouterUrl).append("/routes").toString();
        resource = client.resource(url);
        rt = new Route();
        rt.setSrcNetworkAddr("0.0.0.0");
        rt.setSrcNetworkLength(0);
        rt.setDstNetworkAddr("14.128.23.0");
        rt.setDstNetworkLength(27);
        rt.setType(Route.Reject);
        rt.setWeight(1000);  // High weight - low priority.
        response = resource.type(MediaType.APPLICATION_JSON)
                .header("HTTP_X_AUTH_TOKEN", "999888777666")
                .post(ClientResponse.class, rt);

        // Now create a Tenant router.
        url = new StringBuilder(tenantUrl).append(
                "/routers").toString();
        resource = client.resource(url);
        rtr = new Router();
        rtr.setName("TenantRouter");
        response = resource.type(MediaType.APPLICATION_JSON)
                .header("HTTP_X_AUTH_TOKEN", "999888777666")
                .post(ClientResponse.class, rtr);
        String tenantRouterUrl = response.getLocation().toString();
        System.out.println(tenantRouterUrl);
        parts = tenantRouterUrl.split("/");
        UUID tenantRouterId = UUID.fromString(parts[parts.length-1]);

        // Now link the Provider and Tenant routers. Create it from the
        // Provider's perspective.
        url = new StringBuilder(providerRouterUrl).append("/routers").toString();
        resource = client.resource(url);
        LogicalRouterPort logPort = new LogicalRouterPort();
        logPort.setNetworkAddress("192.168.111.0");
        logPort.setNetworkLength(30);
        logPort.setPortAddress("192.168.111.1");
        logPort.setPeerPortAddress("192.168.111.2");
        logPort.setPeerRouterId(tenantRouterId);
        PeerRouterLink routerLink = resource.type(MediaType.APPLICATION_JSON)
                .header("HTTP_X_AUTH_TOKEN", "999888777666")
                .post(PeerRouterLink.class, logPort);
        UUID tenantUplink = routerLink.getPeerPortId();
        System.out.println("Provider's logical port id: "
                + routerLink.getPortId().toString());
        System.out.println("Tenant's logical port id: "
                + tenantUplink.toString());

        // Now create a route on the Provider router that sends traffic for 
        // 14.128.23.0/30 to the tenant router.
        url = new StringBuilder(providerRouterUrl).append("/routes").toString();
        resource = client.resource(url);
        rt = new Route();
        rt.setSrcNetworkAddr("0.0.0.0");
        rt.setSrcNetworkLength(0);
        rt.setDstNetworkAddr("14.128.23.0");
        rt.setDstNetworkLength(30);
        rt.setType(Route.Normal);
        rt.setNextHopPort(routerLink.getPortId());
        rt.setWeight(100);
        response = resource.type(MediaType.APPLICATION_JSON)
                .header("HTTP_X_AUTH_TOKEN", "999888777666")
                .post(ClientResponse.class, rt);

        // Now create a default route on the Tenant router that sends all
        // traffic to the Provider
        url = new StringBuilder(tenantRouterUrl).append("/routes").toString();
        resource = client.resource(url);
        rt = new Route();
        rt.setSrcNetworkAddr("0.0.0.0");
        rt.setSrcNetworkLength(0);
        rt.setDstNetworkAddr("0.0.0.0");
        rt.setDstNetworkLength(0);
        rt.setType(Route.Normal);
        rt.setNextHopPort(tenantUplink);
        rt.setWeight(1000);  // High weight - low priority.
        response = resource.type(MediaType.APPLICATION_JSON)
                .header("HTTP_X_AUTH_TOKEN", "999888777666")
                .post(ClientResponse.class, rt);

        // Create a route on the Tenant router that silently drops all
        // traffic to private IPs. This avoids sending private packets
        // to the provider router.
        url = new StringBuilder(tenantRouterUrl).append("/routes").toString();
        resource = client.resource(url);
        rt = new Route();
        rt.setSrcNetworkAddr("0.0.0.0");
        rt.setSrcNetworkLength(0);
        rt.setDstNetworkAddr("10.0.0.0");
        rt.setDstNetworkLength(8);
        rt.setType(Route.BlackHole);
        rt.setWeight(1000);  // High weight - low priority.
        response = resource.type(MediaType.APPLICATION_JSON)
                .header("HTTP_X_AUTH_TOKEN", "999888777666")
                .post(ClientResponse.class, rt);

        // Now create a route on the Tenant router that rejects all traffic
        // to 14.128.23.0/30. This prevents routing loops.
        url = new StringBuilder(tenantRouterUrl).append("/routes").toString();
        resource = client.resource(url);
        rt = new Route();
        rt.setSrcNetworkAddr("0.0.0.0");
        rt.setSrcNetworkLength(0);
        rt.setDstNetworkAddr("14.128.23.0");
        rt.setDstNetworkLength(30);
        rt.setType(Route.Reject);
        rt.setWeight(1000);  // High weight - low priority.
        response = resource.type(MediaType.APPLICATION_JSON)
                .header("HTTP_X_AUTH_TOKEN", "999888777666")
                .post(ClientResponse.class, rt);

        // Now add a port for 10.0.4.0/24 on the Tenant router.
        url = new StringBuilder(tenantRouterUrl).append("/ports").toString();
        resource = client.resource(url);
        materPort.setNetworkAddress("10.0.4.0");
        materPort.setNetworkLength(24);
        materPort.setPortAddress("10.0.4.1");
        materPort.setLocalNetworkAddress("10.0.4.0");
        materPort.setLocalNetworkLength(24);
        response = resource.type(MediaType.APPLICATION_JSON)
                .header("HTTP_X_AUTH_TOKEN", "999888777666")
                .post(ClientResponse.class, materPort);
        portPath = response.getLocation().toString();
        parts = portPath.split("/");
        portId = parts[parts.length - 1];
        System.out.println("Port for 10.0.4.0/24 has id: " + portId);

        // Add a route to 10.0.4.0/24
        url = new StringBuilder(tenantRouterUrl).append("/routes").toString();
        resource = client.resource(url);
        rt = new Route();
        rt.setSrcNetworkAddr("0.0.0.0");
        rt.setSrcNetworkLength(0);
        rt.setDstNetworkAddr("10.0.4.0");
        rt.setDstNetworkLength(24);
        rt.setType(Route.Normal);
        rt.setNextHopPort(UUID.fromString(portId));
        rt.setWeight(10);
        response = resource.type(MediaType.APPLICATION_JSON)
                .header("HTTP_X_AUTH_TOKEN", "999888777666")
                .post(ClientResponse.class, rt);

        // Now add a port for 10.0.5.0/24
        url = new StringBuilder(tenantRouterUrl).append("/ports").toString();
        resource = client.resource(url);
        materPort.setNetworkAddress("10.0.5.0");
        materPort.setNetworkLength(24);
        materPort.setPortAddress("10.0.5.1");
        materPort.setLocalNetworkAddress("10.0.5.0");
        materPort.setLocalNetworkLength(24);
        response = resource.type(MediaType.APPLICATION_JSON)
                .header("HTTP_X_AUTH_TOKEN", "999888777666")
                .post(ClientResponse.class, materPort);
        portPath = response.getLocation().toString();
        parts = portPath.split("/");
        portId = parts[parts.length - 1];
        System.out.println("Port for 10.0.5.0/24 has id: " + portId);

        // Add a route to 10.0.5.0/24
        url = new StringBuilder(tenantRouterUrl).append("/routes").toString();
        resource = client.resource(url);
        rt.setSrcNetworkAddr("0.0.0.0");
        rt.setSrcNetworkLength(0);
        rt.setDstNetworkAddr("10.0.5.0");
        rt.setDstNetworkLength(24);
        rt.setType(Route.Normal);
        rt.setNextHopPort(UUID.fromString(portId));
        rt.setWeight(10);
        response = resource.type(MediaType.APPLICATION_JSON)
                .header("HTTP_X_AUTH_TOKEN", "999888777666")
                .post(ClientResponse.class, rt);

        // Get the paths to the tenant router's pre- and post-routing chains.
        url = new StringBuilder(tenantRouterUrl)
                .append("/tables/nat/chains/pre_routing").toString();
        resource = client.resource(url);
        Chain chain = resource.type(MediaType.APPLICATION_JSON)
                .header("HTTP_X_AUTH_TOKEN", "999888777666").get(Chain.class);
        String pre_chain = chain.getId().toString();
        System.out.println("Pre-routing chain id: " + pre_chain);

        url = new StringBuilder(tenantRouterUrl).append(
                "/tables/nat/chains/post_routing").toString();
        resource = client.resource(url);
        chain = resource.type(MediaType.APPLICATION_JSON)
                .header("HTTP_X_AUTH_TOKEN", "999888777666").get(Chain.class);
        String post_chain = chain.getId().toString();
        System.out.println("Post-routing chain id: " + pre_chain);

        // Add rules to assign 14.128.23.0 as a floating ip to 10.0.4.10.
        // First the DNAT
        url = new StringBuilder(basePath).append("/chains/").append(pre_chain)
                .append("/rules").toString();
        resource = client.resource(url);
        Rule rule = new Rule();
        rule.setInPorts(new UUID[] { tenantUplink });
        // Leave nwProto wildcarded so that we can receive udp/tcp/icmp
        rule.setNwDstAddress("14.128.23.0");
        rule.setNwDstLength(32);
        rule.setType(Rule.DNAT);
        rule.setFlowAction(Rule.Accept);
        String[][][] target = new String[1][2][];
        target[0][0] = new String[] { "10.0.4.10", "10.0.4.10" };
        target[0][1] = new String[] { "0", "0" };
        rule.setNatTargets(target);
        rule.setPosition(1);
        response = resource.type(MediaType.APPLICATION_JSON)
                .header("HTTP_X_AUTH_TOKEN", "999888777666")
                .post(ClientResponse.class, rule);
        // Now the SNAT
        url = new StringBuilder(basePath).append("/chains/").append(post_chain)
                .append("/rules").toString();
        resource = client.resource(url);
        rule = new Rule();
        rule.setOutPorts(new UUID[] { tenantUplink });
        // Leave nwProto wildcarded.
        rule.setNwSrcAddress("10.0.4.10");
        rule.setNwSrcLength(32);
        rule.setType(Rule.SNAT);
        rule.setFlowAction(Rule.Accept);
        target[0][0] = new String[] { "14.128.23.0", "14.128.23.0" };
        target[0][1] = new String[] { "0", "0" };
        rule.setNatTargets(target);
        rule.setPosition(1);
        response = resource.type(MediaType.APPLICATION_JSON)
                .header("HTTP_X_AUTH_TOKEN", "999888777666")
                .post(ClientResponse.class, rule);

        // Now set up TCP SNAT for all hosts in 10.0.5.0/24 to 14.128.23.1.
        url = new StringBuilder(basePath).append("/chains/").append(post_chain)
                .append("/rules").toString();
        resource = client.resource(url);
        rule = new Rule();
        rule.setOutPorts(new UUID[] { tenantUplink });
        rule.setNwProto(6); // TCP
        rule.setNwSrcAddress("10.0.5.0");
        rule.setNwSrcLength(24);
        rule.setType(Rule.SNAT);
        rule.setFlowAction(Rule.Accept);
        target[0][0] = new String[] { "14.128.23.1", "14.128.23.1" };
        target[0][1] = new String[] { "40000", "50000" };
        rule.setNatTargets(target);
        rule.setPosition(2);
        response = resource.type(MediaType.APPLICATION_JSON)
                .header("HTTP_X_AUTH_TOKEN", "999888777666")
                .post(ClientResponse.class, rule);
        // Now the ReverseSNAT
        url = new StringBuilder(basePath).append("/chains/").append(pre_chain)
                .append("/rules").toString();
        resource = client.resource(url);
        rule = new Rule();
        rule.setInPorts(new UUID[] { tenantUplink });
        rule.setNwProto(6); // TCP
        rule.setNwDstAddress("14.128.23.1");
        rule.setNwDstLength(32);
        rule.setTpDstStart((short) 40000);
        rule.setTpDstEnd((short) 50000);
        rule.setType(Rule.RevSNAT);
        rule.setFlowAction(Rule.Accept);
        rule.setPosition(2);
        response = resource.type(MediaType.APPLICATION_JSON)
                .header("HTTP_X_AUTH_TOKEN", "999888777666")
                .post(ClientResponse.class, rule);

        // Now set up a TCP DNAT: 14.128.23.2:80 to 10.0.5.10 and 10.0.5.11.
        url = new StringBuilder(basePath).append("/chains/").append(pre_chain)
                .append("/rules").toString();
        resource = client.resource(url);
        rule = new Rule();
        rule.setInPorts(new UUID[] { tenantUplink });
        rule.setNwProto(6); // TCP
        rule.setNwDstAddress("14.128.23.2");
        rule.setNwDstLength(32);
        rule.setType(Rule.DNAT);
        rule.setFlowAction(Rule.Accept);
        target[0][0] = new String[] { "10.0.5.10", "10.0.5.11" };
        target[0][1] = new String[] { "80", "80" };
        rule.setNatTargets(target);
        rule.setPosition(3);
        response = resource.type(MediaType.APPLICATION_JSON)
                .header("HTTP_X_AUTH_TOKEN", "999888777666")
                .post(ClientResponse.class, rule);
        // Now the ReverseDNAT
        url = new StringBuilder(basePath).append("/chains/").append(post_chain)
                .append("/rules").toString();
        resource = client.resource(url);
        rule = new Rule();
        rule.setOutPorts(new UUID[] { tenantUplink });
        rule.setNwProto(17);
        rule.setNwSrcAddress("10.0.5.8");
        rule.setNwSrcLength(30);
        rule.setType(Rule.RevDNAT);
        rule.setFlowAction(Rule.Accept);
        rule.setPosition(3);
        response = resource.type(MediaType.APPLICATION_JSON)
                .header("HTTP_X_AUTH_TOKEN", "999888777666")
                .post(ClientResponse.class, rule);
    }
}
