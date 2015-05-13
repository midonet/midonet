package org.midonet.cluster.rest_api.conversion;

import java.net.URI;

import org.midonet.cluster.data.Router.Property;
import org.midonet.cluster.rest_api.models.Router;

public class RouterDataConverter {

    public static Router fromData(org.midonet.cluster.data.Router routerData,
                                  URI baseUri) {
        Router r = new Router(baseUri);
        r.id = routerData.getId();
        r.name = routerData.getName();
        r.adminStateUp = routerData.isAdminStateUp();
        r.tenantId = routerData.getProperty(Property.tenant_id);
        r.inboundFilterId = routerData.getInboundFilter();
        r.outboundFilterId = routerData.getOutboundFilter();
        r.loadBalancerId = routerData.getLoadBalancer();
        return r;
    }

    public static org.midonet.cluster.data.Router toData(Router r) {
        return new org.midonet.cluster.data.Router()
            .setId(r.id)
            .setName(r.name)
            .setAdminStateUp(r.adminStateUp)
            .setInboundFilter(r.inboundFilterId)
            .setOutboundFilter(r.outboundFilterId)
            .setLoadBalancer(r.loadBalancerId)
            .setProperty(Property.tenant_id, r.tenantId);
    }

}
