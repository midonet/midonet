/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.io;

import java.io.IOException;

import com.midokura.midolman.mgmt.data.dto.config.VifConfig;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.util.Serializer;

/**
 * ZK VIF serializer class.
 */
public class VifSerializer {

    private Serializer<VifConfig> serializer = null;

    /**
     * Constructor
     *
     * @param serializer
     *            Serializer to use for VifConfig.
     */
    public VifSerializer(Serializer<VifConfig> serializer) {
        this.serializer = serializer;
    }

    /**
     * Deserialize VifConfig object.
     *
     * @param data
     *            Byte array to deserialize from.
     * @return VifConfig object.
     * @throws ZkStateSerializationException
     *             Serialization error.
     */
    public VifConfig deserialize(byte[] data)
            throws ZkStateSerializationException {
        try {
            return serializer.bytesToObj(data, VifConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize VifConfig.", e, VifConfig.class);
        }
    }

    /**
     * Serialize VifConfig object.
     *
     * @param config
     *            VifConfig object.
     * @return Byte array.
     * @throws ZkStateSerializationException
     *             Serialization error.
     */
    public byte[] serialize(VifConfig config)
            throws ZkStateSerializationException {
        try {
            return serializer.objToBytes(config);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize VifConfig", e, VifConfig.class);
        }
    }
}
