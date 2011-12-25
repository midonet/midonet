/*
 * @(#)PortSerializer        1.6 11/11/29
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.io;

import java.io.IOException;

import com.midokura.midolman.mgmt.data.dto.config.PortMgmtConfig;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.util.Serializer;

/**
 * ZK Port serializer class.
 *
 * @version 1.6 29 Nov 2011
 * @author Ryu Ishimoto
 */
public class PortSerializer {

    private Serializer<PortMgmtConfig> serializer = null;

    /**
     * Constructor
     *
     * @param serializer
     *            Serializer to use for PortMgmtConfig.
     */
    public PortSerializer(Serializer<PortMgmtConfig> serializer) {
        this.serializer = serializer;
    }

    /**
     * Deserialize PortMgmtConfig object.
     *
     * @param data
     *            Byte array to deserialize from.
     * @return PortMgmtConfig object.
     * @throws ZkStateSerializationException
     *             Serialization error.
     */
    public PortMgmtConfig deserialize(byte[] data)
            throws ZkStateSerializationException {
        try {
            return serializer.bytesToObj(data, PortMgmtConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize PortMgmtConfig.", e,
                    PortMgmtConfig.class);
        }
    }

    /**
     * Serialize PortMgmtConfig object.
     *
     * @param config
     *            PortMgmtConfig object.
     * @return Byte array.
     * @throws ZkStateSerializationException
     *             Serialization error.
     */
    public byte[] serialize(PortMgmtConfig config)
            throws ZkStateSerializationException {
        try {
            return serializer.objToBytes(config);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize PortMgmtConfig", e,
                    PortMgmtConfig.class);
        }
    }
}
