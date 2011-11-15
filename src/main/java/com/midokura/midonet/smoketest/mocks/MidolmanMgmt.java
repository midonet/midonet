package com.midokura.midonet.smoketest.mocks;

import java.net.URI;

import com.midokura.midolman.mgmt.data.dto.MaterializedRouterPort;
import com.midokura.midolman.mgmt.data.dto.Route;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.data.dto.Tenant;

public interface MidolmanMgmt {

    URI addTenant(Tenant t);

    URI addRouter(URI tenantURI, Router r);

    URI addRouterPort(URI routerURI, MaterializedRouterPort p);

    URI addRoute(URI routerURI, Route rt);

    <T> T get(String path, Class<T> clazz);

    void delete(String path);

}
