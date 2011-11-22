package com.midokura.midonet.smoketest.topology;

import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midonet.smoketest.mgmt.DtoRouter;
import com.midokura.midonet.smoketest.mgmt.DtoTenant;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;

public class Router {

    public static class Builder {
        MidolmanMgmt mgmt;
        DtoTenant tenant;
        DtoRouter router;

        public Builder(MidolmanMgmt mgmt, DtoTenant tenant) {
            this.mgmt = mgmt;
            this.tenant = tenant;
            this.router = new DtoRouter();
        }

        public Builder setName(String name) {
            router.setName(name);
            return this;
        }

        public Router build() {
            if (null == router.getName() || router.getName().isEmpty())
                throw new IllegalArgumentException("Cannot create a "
                        + "router with a null or empty name.");
            return new Router(mgmt, mgmt.addRouter(tenant, router));
        }
    }

    MidolmanMgmt mgmt;
    DtoRouter dto;

    Router(MidolmanMgmt mgmt, DtoRouter router) {
        this.mgmt = mgmt;
        this.dto = router;
    }

    public VPortBuilder addPort(OpenvSwitchDatabaseConnection ovsdb) {
        return new VPortBuilder(ovsdb, mgmt, dto);
    }

    public PeerRouterLink.Builder addRouterLink() {
        return new PeerRouterLink.Builder(mgmt, dto);
    }

    public void delete() {
        
    }

}
