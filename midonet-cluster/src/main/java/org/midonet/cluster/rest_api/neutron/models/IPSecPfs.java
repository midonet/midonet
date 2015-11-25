package org.midonet.cluster.rest_api.neutron.models;

import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.models.Neutron;

@ZoomEnum(clazz = Neutron.IPSecPfs.class)
public enum IPSecPfs {
    @ZoomEnumValue("GROUP2") GROUP_2,
    @ZoomEnumValue("GROUP5") GROUP_5,
    @ZoomEnumValue("GROUP14") GROUP_14
}
