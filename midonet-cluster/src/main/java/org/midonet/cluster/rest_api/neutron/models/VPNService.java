package org.midonet.cluster.rest_api.neutron.models;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.ws.rs.core.UriBuilder;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Neutron;
import org.midonet.cluster.rest_api.models.UriResource;
import org.midonet.cluster.util.IPAddressUtil;
import org.midonet.packets.IPAddr;

@ZoomClass(clazz = Neutron.VPNService.class)
public class VPNService extends UriResource {
    @ZoomField(name = "id")
    public UUID id;

    @ZoomField(name = "name")
    public String name;

    @ZoomField(name = "description")
    public String description;

    @ZoomField(name = "admin_state_up")
    public Boolean adminStateUp;

    @ZoomField(name = "tenant_id")
    public String tenantId;

    @ZoomField(name = "router_id")
    public UUID routerId;

    @ZoomField(name = "subnet_id")
    public UUID subnetId;

    @ZoomField(name = "status")
    public String status;

    @ZoomField(name = "external_v4_ip", converter = IPAddressUtil.Converter.class)
    public IPAddr externalV4Ip;

    @ZoomField(name = "external_v6_ip", converter = IPAddressUtil.Converter.class)
    public IPAddr externalV6Ip;

    @Override
    public URI getUri() {
        if (getBaseUri() == null) {
            return null;
        } else {
            return UriBuilder.fromUri(getBaseUri())
                .path("neutron")
                .path("vpnservices")
                .path(id.toString()).build();
        }
    }

    @Override
    public void create() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        VPNService that = (VPNService) o;

        return Objects.equals(id, that.id) &&
               Objects.equals(name, that.name) &&
               Objects.equals(description, that.description) &&
               Objects.equals(adminStateUp, that.adminStateUp) &&
               Objects.equals(tenantId, that.tenantId) &&
               Objects.equals(routerId, that.routerId) &&
               Objects.equals(subnetId, that.subnetId) &&
               Objects.equals(externalV4Ip, that.externalV4Ip) &&
               Objects.equals(externalV6Ip, that.externalV6Ip) &&
               Objects.equals(status, that.status);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, description, adminStateUp, tenantId,
                            routerId, subnetId, status, externalV4Ip,
                            externalV6Ip);
    }
}