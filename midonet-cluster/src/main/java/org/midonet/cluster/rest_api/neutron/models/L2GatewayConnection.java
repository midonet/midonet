package org.midonet.cluster.rest_api.neutron.models;

import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Neutron;

@ZoomClass(clazz = Neutron.L2GatewayConnection.class)
public class L2GatewayConnection extends ZoomObject {
    @ZoomField(name = "id")
    public UUID id;

    @JsonProperty("tenant_id")
    @ZoomField(name = "tenant_id")
    public String tenantId;

    @JsonProperty("router_id")
    @ZoomField(name = "router_id")
    public UUID routerId;

    @JsonProperty("network_id")
    @ZoomField(name = "network_id")
    public UUID networkId;

    @JsonProperty("segmentation_id")
    @ZoomField(name = "segmentation_id")
    public Integer segmentationId;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        L2GatewayConnection that = (L2GatewayConnection) o;

        return Objects.equals(id, that.id) &&
               Objects.equals(tenantId, that.tenantId) &&
               Objects.equals(routerId, that.routerId) &&
               Objects.equals(networkId, that.networkId) &&
               Objects.equals(segmentationId, that.segmentationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, tenantId, routerId, networkId, segmentationId);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .omitNullValues()
            .add("id", id)
            .add("tenantId", tenantId)
            .add("routerId", routerId)
            .add("networkId", networkId)
            .add("segmentationId", segmentationId)
            .toString();
    }
}
