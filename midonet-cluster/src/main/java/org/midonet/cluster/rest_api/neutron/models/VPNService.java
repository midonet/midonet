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
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.packets.IPAddr;

@ZoomClass(clazz = Neutron.VPNService.class)
public class VPNService extends UriResource {
    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID id;

    @ZoomField(name = "name")
    public String name;

    @ZoomField(name = "description")
    public String description;

    @ZoomField(name = "admin_state_up")
    public Boolean adminStateUp;

    @ZoomField(name = "tenant_id")
    public String tenantId;

    @ZoomField(name = "router_id", converter = UUIDUtil.Converter.class)
    public UUID routerId;

    @ZoomField(name = "subnet_id", converter = UUIDUtil.Converter.class)
    public UUID subnetId;

    @ZoomField(name = "status")
    public String status;

    @ZoomField(name = "external_ips", converter = IPAddressUtil.Converter.class)
    public List<IPAddr> externalIps;

    @Override
    public URI getUri() {
        if (getBaseUri() == null) {
            return null;
        } else {
            // TODO: make relative based on Neutron.getUri()
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

        // Treat empty list as equivalent to null
        if (externalIps == null && that.externalIps != null
            && !that.externalIps.isEmpty()) {
            return false;
        }
        if (externalIps != null && !externalIps.isEmpty()
            && that.externalIps == null) {
            return false;
        }

        return Objects.equals(id, that.id) &&
               Objects.equals(name, that.name) &&
               Objects.equals(description, that.description) &&
               Objects.equals(adminStateUp, that.adminStateUp) &&
               Objects.equals(tenantId, that.tenantId) &&
               Objects.equals(routerId, that.routerId) &&
               Objects.equals(subnetId, that.subnetId) &&
               Objects.equals(status, that.status);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, description, adminStateUp, tenantId,
                            routerId, subnetId, status, externalIps);
    }
}
