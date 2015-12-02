package org.midonet.cluster.rest_api.neutron.models;

import com.fasterxml.jackson.annotation.JsonCreator;

import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.models.Neutron;

@ZoomEnum(clazz = Neutron.IPSecPFS.class)
public enum IPSecPFS {
    @ZoomEnumValue("GROUP_2") GROUP_2,
    @ZoomEnumValue("GROUP_5") GROUP_5,
    @ZoomEnumValue("GROUP_14") GROUP_14;

    @JsonCreator
    @SuppressWarnings("unused")
    public static IPSecPFS forValue(String v) {
        return valueOf(IPSecSiteConnection.convertFromIpsecString(v));
    }
}
