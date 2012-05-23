/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.io;

import java.io.IOException;

import com.midokura.midolman.mgmt.data.dto.config.ChainMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.ChainNameMgmtConfig;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.util.Serializer;

/**
 * ZK Chain serializer class.
 */
public class ChainSerializer {

    private Serializer<ChainMgmtConfig> serializer = null;
    private Serializer<ChainNameMgmtConfig> nameSerializer = null;

    /**
     * Constructor.
     *
     * @param serializer
     *            ChainMgmtConfig serializer.
     * @param nameSerializer
     *            ChainNameMgmtConfig serializer.
     */
    public ChainSerializer(Serializer<ChainMgmtConfig> serializer,
            Serializer<ChainNameMgmtConfig> nameSerializer) {
        this.serializer = serializer;
        this.nameSerializer = nameSerializer;
    }

    /**
     * Deserialize ChainMgmtConfig object.
     *
     * @param data
     *            Byte array to deserialize from.
     * @return ChainMgmtConfig object.
     * @throws ZkStateSerializationException
     *             Serialization error.
     */
    public ChainMgmtConfig deserialize(byte[] data)
            throws ZkStateSerializationException {
        try {
            return serializer.bytesToObj(data, ChainMgmtConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize ChainMgmtConfig.", e,
                    ChainMgmtConfig.class);
        }
    }

    /**
     * Deserialize ChainNameMgmtConfig object.
     *
     * @param data
     *            Byte array to deserialize from.
     * @return ChainNameMgmtConfig object.
     * @throws ZkStateSerializationException
     *             Serialization error.
     */
    public ChainNameMgmtConfig deserializeName(byte[] data)
            throws ZkStateSerializationException {
        try {
            return nameSerializer.bytesToObj(data, ChainNameMgmtConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize ChainNameMgmtConfig.", e,
                    ChainNameMgmtConfig.class);
        }
    }

    /**
     * Serialize ChainMgmtConfig object.
     *
     * @param config
     *            ChainMgmtConfig object.
     * @return byte array.
     * @throws ZkStateSerializationException
     *             Serialization error.
     */
    public byte[] serialize(ChainMgmtConfig config)
            throws ZkStateSerializationException {
        try {
            return serializer.objToBytes(config);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize ChainMgmtConfig", e,
                    ChainMgmtConfig.class);
        }
    }

    /**
     * Serialize ChainNameMgmtConfig object.
     *
     * @param config
     *            ChainNameMgmtConfig object.
     * @return byte array.
     * @throws ZkStateSerializationException
     *             Serialization error.
     */
    public byte[] serialize(ChainNameMgmtConfig config)
            throws ZkStateSerializationException {
        try {
            return nameSerializer.objToBytes(config);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize ChainNameMgmtConfig", e,
                    ChainNameMgmtConfig.class);
        }
    }
}
