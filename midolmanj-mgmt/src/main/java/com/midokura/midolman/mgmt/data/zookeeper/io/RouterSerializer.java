/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.io;

import java.io.IOException;

import com.midokura.midolman.mgmt.data.dto.config.RouterMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.RouterNameMgmtConfig;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.util.Serializer;

/**
 * ZK Router serializer class.
 */
public class RouterSerializer {

    private final Serializer<RouterMgmtConfig> serializer;
    private final Serializer<RouterNameMgmtConfig> nameSerializer;

    /**
     * Constructor.
     * 
     * @param serializer
     *            RouterMgmtConfig serializer.
     * @param nameSerializer
     *            RouterNameMgmtConfig serializer.
     */
    public RouterSerializer(Serializer<RouterMgmtConfig> serializer,
            Serializer<RouterNameMgmtConfig> nameSerializer) {
        this.serializer = serializer;
        this.nameSerializer = nameSerializer;
    }

    /**
     * Deserialize RouterMgmtConfig object.
     * 
     * @param data
     *            Byte array to deserialize from.
     * @return RouterMgmtConfig object.
     * @throws ZkStateSerializationException
     *             Serialization error.
     */
    public RouterMgmtConfig deserialize(byte[] data)
            throws ZkStateSerializationException {
        try {
            return serializer.bytesToObj(data, RouterMgmtConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize RouterMgmtConfig.", e,
                    RouterMgmtConfig.class);
        }
    }

    /**
     * Deserialize RouterNameMgmtConfig object.
     * 
     * @param data
     *            Byte array to deserialize from.
     * @return RouterNameMgmtConfig object.
     * @throws ZkStateSerializationException
     *             Serialization error.
     */
    public RouterNameMgmtConfig deserializeName(byte[] data)
            throws ZkStateSerializationException {
        try {
            return nameSerializer.bytesToObj(data, RouterNameMgmtConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize RouterNameMgmtConfig.", e,
                    RouterNameMgmtConfig.class);
        }
    }

    /**
     * Serialize RouterMgmtConfig object.
     * 
     * @param config
     *            RouterMgmtConfig object.
     * @return byte array.
     * @throws ZkStateSerializationException
     *             Serialization error.
     */
    public byte[] serialize(RouterMgmtConfig config)
            throws ZkStateSerializationException {
        try {
            return serializer.objToBytes(config);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize RouterMgmtConfig", e,
                    RouterMgmtConfig.class);
        }
    }

    /**
     * Serialize RouterNameMgmtConfig object.
     * 
     * @param config
     *            RouterNameMgmtConfig object.
     * @return byte array.
     * @throws ZkStateSerializationException
     *             Serialization error.
     */
    public byte[] serialize(RouterNameMgmtConfig config)
            throws ZkStateSerializationException {
        try {
            return nameSerializer.objToBytes(config);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize RouterNameMgmtConfig", e,
                    RouterNameMgmtConfig.class);
        }
    }
}
