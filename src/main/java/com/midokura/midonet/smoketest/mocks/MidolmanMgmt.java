package com.midokura.midonet.smoketest.mocks;

import java.net.URI;

import com.midokura.midonet.smoketest.mgmt.DtoMaterializedRouterPort;
import com.midokura.midonet.smoketest.mgmt.DtoRoute;
import com.midokura.midonet.smoketest.mgmt.DtoRouter;
import com.midokura.midonet.smoketest.mgmt.DtoTenant;

public interface MidolmanMgmt {

    DtoTenant addTenant(DtoTenant t);

    DtoRouter addRouter(DtoTenant t, DtoRouter r);

    DtoMaterializedRouterPort addRouterPort(DtoRouter r,
            DtoMaterializedRouterPort p);

    DtoRoute addRoute(DtoRouter r, DtoRoute rt);

    <T> T get(String path, Class<T> clazz);

    void delete(URI uri);

}
