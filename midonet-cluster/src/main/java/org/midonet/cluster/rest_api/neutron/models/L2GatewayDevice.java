package org.midonet.cluster.rest_api.neutron.models;

import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Neutron;

@ZoomClass(clazz = Neutron.L2Gateway.L2GatewayDevice.class)
public class L2GatewayDevice extends ZoomObject {

    @ZoomField(name = "device")
    public GatewayDevice device;

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

        L2GatewayDevice that = (L2GatewayDevice) o;

        return Objects.equals(device, that.device) &&
               Objects.equals(segmentationId, that.segmentationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(device, segmentationId);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .omitNullValues()
            .add("device", device)
            .add("segmentationId", segmentationId)
            .toString();
    }
}
