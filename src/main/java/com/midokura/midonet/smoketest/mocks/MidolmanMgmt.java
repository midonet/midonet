package com.midokura.midonet.smoketest.mocks;

import java.net.URI;

import com.midokura.midolman.mgmt.data.dto.MaterializedRouterPort;
import com.midokura.midolman.mgmt.data.dto.Route;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.data.dto.Tenant;

public interface MidolmanMgmt {

    Tenant addTenant(Tenant t);

    Router addRouter(URI tenantRoutersURI, Router r);

    MaterializedRouterPort addRouterPort(URI routerURI,
            MaterializedRouterPort p);

    Route addRoute(URI routerURI, Route rt);

    <T> T get(String path, Class<T> clazz);

    void delete(URI uri);

}
