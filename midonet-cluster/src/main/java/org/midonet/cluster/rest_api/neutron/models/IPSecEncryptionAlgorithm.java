package org.midonet.cluster.rest_api.neutron.models;

import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.models.Neutron;

@ZoomEnum(clazz = Neutron.IPSecSiteConnection.IPSecEncryptionAlgorithm.class)
public enum IPSecEncryptionAlgorithm {
    @ZoomEnumValue("DES_3") DES_3,
    @ZoomEnumValue("AES_128") AES_128,
    @ZoomEnumValue("AES_192") AES_192,
    @ZoomEnumValue("AES_256") AES_256;
}
