package org.midonet.cluster.rest_api.neutron.models;

import com.fasterxml.jackson.annotation.JsonCreator;

import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.models.Neutron;

@ZoomEnum(clazz = Neutron.IPSecEncryptionAlgorithm.class)
public enum IPSecEncryptionAlgorithm {
    @ZoomEnumValue("DES_3") DES_3,
    @ZoomEnumValue("AES_128") AES_128,
    @ZoomEnumValue("AES_192") AES_192,
    @ZoomEnumValue("AES_256") AES_256;

    @JsonCreator
    @SuppressWarnings("unused")
    public static IPSecEncryptionAlgorithm forValue(String v) {
        return valueOf(IPSecSiteConnection.convertFromIpsecString(v));
    }
}
