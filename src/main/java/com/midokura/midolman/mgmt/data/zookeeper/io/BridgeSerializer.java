/*
 * @(#)BridgeSerializer        1.6 11/11/29
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.io;

import java.io.IOException;

import com.midokura.midolman.mgmt.data.dto.config.BridgeMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.BridgeNameMgmtConfig;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.util.Serializer;

/**
 * ZK Bridge serializer class.
 *
 * @version 1.6 29 Nov 2011
 * @author Ryu Ishimoto
 */
public class BridgeSerializer {

    private Serializer<BridgeMgmtConfig> serializer = null;
    private Serializer<BridgeNameMgmtConfig> nameSerializer = null;

    /**
     * Constructor.
     *
     * @param serializer
     *            BridgeMgmtConfig serializer.
     * @param nameSerializer
     *            BridgeNameMgmtConfig serializer.
     */
    public BridgeSerializer(Serializer<BridgeMgmtConfig> serializer,
            Serializer<BridgeNameMgmtConfig> nameSerializer) {
        this.serializer = serializer;
        this.nameSerializer = nameSerializer;
    }

    /**
     * Deserialize BridgeMgmtConfig object.
     *
     * @param data
     *            Byte array to deserialize from.
     * @return BridgeMgmtConfig object.
     * @throws ZkStateSerializationException
     *             Serialization error.
     */
    public BridgeMgmtConfig deserialize(byte[] data)
            throws ZkStateSerializationException {
        try {
            return serializer.bytesToObj(data, BridgeMgmtConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize BridgeMgmtConfig.", e,
                    BridgeMgmtConfig.class);
        }
    }

    /**
     * Deserialize BridgeNameMgmtConfig object.
     *
     * @param data
     *            Byte array to deserialize from.
     * @return BridgeNameMgmtConfig object.
     * @throws ZkStateSerializationException
     *             Serialization error.
     */
    public BridgeNameMgmtConfig deserializeName(byte[] data)
            throws ZkStateSerializationException {
        try {
            return nameSerializer.bytesToObj(data, BridgeNameMgmtConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize BridgeNameMgmtConfig.", e,
                    BridgeNameMgmtConfig.class);
        }
    }

    /**
     * Serialize BridgeMgmtConfig object.
     *
     * @param config
     *            BridgeMgmtConfig object.
     * @return byte array.
     * @throws ZkStateSerializationException
     *             Serialization error.
     */
    public byte[] serialize(BridgeMgmtConfig config)
            throws ZkStateSerializationException {
        try {
            return serializer.objToBytes(config);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize BridgeMgmtConfig", e,
                    BridgeMgmtConfig.class);
        }
    }

    /**
     * Serialize BridgeNameMgmtConfig object.
     *
     * @param config
     *            BridgeNameMgmtConfig object.
     * @return byte array.
     * @throws ZkStateSerializationException
     *             Serialization error.
     */
    public byte[] serialize(BridgeNameMgmtConfig config)
            throws ZkStateSerializationException {
        try {
            return nameSerializer.objToBytes(config);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize BridgeNameMgmtConfig", e,
                    BridgeNameMgmtConfig.class);
        }
    }

}
