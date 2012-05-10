/*
* Copyright 2011 Midokura Europe SARL
*/
package com.midokura.midonet.functional_test.mocks;

import java.net.URI;

import com.midokura.midolman.mgmt.data.dto.client.*;

public interface MidolmanMgmt {

    DtoTenant addTenant(DtoTenant t);

    DtoRouter addRouter(DtoTenant t, DtoRouter r);

    DtoBridge addBridge(DtoTenant t, DtoBridge b);

    DtoPeerRouterLink linkRouterToPeer(DtoRouter router,
            DtoLogicalRouterPort logPort);

    DtoBridgeRouterLink linkRouterToBridge(
            DtoRouter router, DtoBridgeRouterPort logPort);

    DtoMaterializedRouterPort addRouterPort(DtoRouter r,
            DtoMaterializedRouterPort p);

    DtoPort addBridgePort(DtoBridge b,
            DtoPort p);

    DtoRoute addRoute(DtoRouter r, DtoRoute rt);

    DtoBgp addBGP(DtoMaterializedRouterPort p, DtoBgp b);

    <T> T get(String path, Class<T> clazz);

    void delete(URI uri);

    DtoTenant[] getTenants();

    void deleteTenant(String string);

    DtoAdRoute addBgpAdvertisedRoute(DtoBgp dtoBgp, DtoAdRoute dtpAdRoute);

    DtoRuleChain addRuleChain(DtoTenant tenant, DtoRuleChain chain);

    DtoRuleChain getRuleChain(DtoRouter router, String name);

    DtoRule addRule(DtoRuleChain chain, DtoRule rule);

    DtoVpn addVpn(DtoMaterializedRouterPort p, DtoVpn vpn);

    DtoDhcpSubnet addDhcpSubnet(DtoBridge dtoBridge, DtoDhcpSubnet dhcpSubnet);

    DtoDhcpHost addDhcpSubnetHost(DtoDhcpSubnet dtoSubnet, DtoDhcpHost host);

    void deleteVpn(DtoVpn vpn);

    DtoRoute[] getRoutes(DtoRouter router);

    void stop();

    DtoHost[] getHosts();

    DtoHost getHost(URI uri);

    DtoInterface[] getHostInterfaces(DtoHost host);

    DtoInterface getHostInterface(DtoInterface dtoInterface);

    void addInterface(DtoHost host, DtoInterface dtoInterface);

    void updateInterface(DtoInterface dtoInterface);

}
