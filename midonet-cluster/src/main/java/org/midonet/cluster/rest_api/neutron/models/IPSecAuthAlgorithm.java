package org.midonet.cluster.rest_api.neutron.models;

import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.models.Neutron;

// TODO: this field and the enum is pointless, leave out and add whenever
// needed?
@ZoomEnum(clazz = Neutron.IPSecSiteConnection.IPSecAuthAlgorithm.class)
public enum IPSecAuthAlgorithm {
    @ZoomEnumValue("SHA1") SHA1
}
