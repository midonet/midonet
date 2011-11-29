/*
* Copyright 2011 Midokura Europe SARL
*/
package com.midokura.midonet.smoketest.mocks;

import java.net.URI;

import com.midokura.midonet.smoketest.mgmt.*;

public interface MidolmanMgmt {

    DtoTenant addTenant(DtoTenant t);

    DtoRouter addRouter(DtoTenant t, DtoRouter r);

    DtoPeerRouterLink linkRouterToPeer(DtoRouter router,
            DtoLogicalRouterPort logPort);

    DtoMaterializedRouterPort addRouterPort(DtoRouter r,
            DtoMaterializedRouterPort p);

    DtoRoute addRoute(DtoRouter r, DtoRoute rt);

    DtoBgp addBGP(DtoMaterializedRouterPort p, DtoBgp b);

    <T> T get(String path, Class<T> clazz);

    void delete(URI uri);

    DtoTenant[] getTenants();

    void deleteTenant(String string);

    DtoAdRoute addBgpAdvertisedRoute(DtoBgp dtoBgp, DtoAdRoute dtpAdRoute);

    DtoRuleChain addRuleChain(DtoRouter router, DtoRuleChain chain);

    DtoRuleChain getRuleChain(DtoRouter router, String name);

    DtoRule addRule(DtoRuleChain chain, DtoRule rule);
}
