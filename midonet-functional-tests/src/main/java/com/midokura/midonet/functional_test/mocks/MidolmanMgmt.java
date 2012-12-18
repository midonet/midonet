/*
* Copyright 2011 Midokura Europe SARL
*/
package com.midokura.midonet.functional_test.mocks;

import java.net.URI;

import com.midokura.midonet.client.dto.*;

public interface MidolmanMgmt {

    DtoRouter addRouter(DtoTenant t, DtoRouter r);

    DtoBridge addBridge(DtoTenant t, DtoBridge b);

    void updateBridge(DtoBridge b);

    void updateRouter(DtoRouter r);

    void updatePort(DtoPort p);

    void linkRouterToPeer(DtoInteriorRouterPort peerPort);

    DtoExteriorRouterPort addExteriorRouterPort(DtoRouter r,
                                                DtoExteriorRouterPort p);

    DtoInteriorRouterPort addInteriorRouterPort(DtoRouter r,
                                              DtoInteriorRouterPort p);

    DtoBridgePort addExteriorBridgePort(DtoBridge b,
                                            DtoBridgePort p);

    DtoInteriorBridgePort addInteriorBridgePort(DtoBridge b,
                                              DtoInteriorBridgePort p);

    DtoRoute addRoute(DtoRouter r, DtoRoute rt);

    DtoBgp addBGP(DtoExteriorRouterPort p, DtoBgp b);

    <T> T get(String path, Class<T> clazz);

    void delete(URI uri);

    DtoAdRoute addBgpAdvertisedRoute(DtoBgp dtoBgp, DtoAdRoute dtpAdRoute);

    DtoRuleChain addRuleChain(DtoTenant tenant, DtoRuleChain chain);

    DtoRuleChain getRuleChain(DtoRouter router, String name);

    DtoRule addRule(DtoRuleChain chain, DtoRule rule);

    DtoVpn addVpn(DtoExteriorRouterPort p, DtoVpn vpn);

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

    DtoPortGroup addPortGroup(DtoTenant tenant, DtoPortGroup group);

}
