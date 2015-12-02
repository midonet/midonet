package org.midonet.cluster.rest_api.neutron.models;

import com.fasterxml.jackson.annotation.JsonCreator;

import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.models.Neutron;

@ZoomEnum(clazz = Neutron.IPSecPFS.class)
public enum IPSecPFS {
    @ZoomEnumValue("GROUP2") GROUP2,
    @ZoomEnumValue("GROUP5") GROUP5,
    @ZoomEnumValue("GROUP14") GROUP14;

    @JsonCreator
    @SuppressWarnings("unused")
    public static IPSecPFS forValue(String v) {
        return valueOf(IPSecSiteConnection.convertFromIpsecString(v));
    }
}
