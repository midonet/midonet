/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.mgmt.data.zookeeper.io;

import java.io.IOException;

import com.midokura.midolman.mgmt.data.dto.config.PortGroupMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.PortGroupNameMgmtConfig;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.util.Serializer;

/**
 * ZK PortGroup serializer class.
 */
public class PortGroupSerializer {

    private Serializer<PortGroupMgmtConfig> serializer = null;
    private Serializer<PortGroupNameMgmtConfig> nameSerializer = null;

    /**
     * Constructor.
     *
     * @param serializer
     *            PortGroupMgmtConfig serializer.
     * @param nameSerializer
     *            PortGroupNameMgmtConfig serializer.
     */
    public PortGroupSerializer(Serializer<PortGroupMgmtConfig> serializer,
            Serializer<PortGroupNameMgmtConfig> nameSerializer) {
        this.serializer = serializer;
        this.nameSerializer = nameSerializer;
    }

    /**
     * Deserialize PortGroupMgmtConfig object.
     *
     * @param data
     *            Byte array to deserialize from.
     * @return PortGroupMgmtConfig object.
     * @throws com.midokura.midolman.state.ZkStateSerializationException
     *             Serialization error.
     */
    public PortGroupMgmtConfig deserialize(byte[] data)
            throws ZkStateSerializationException {
        try {
            return serializer.bytesToObj(data, PortGroupMgmtConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize PortGroupMgmtConfig.", e,
                    PortGroupMgmtConfig.class);
        }
    }

    /**
     * Deserialize PortGroupNameMgmtConfig object.
     *
     * @param data
     *            Byte array to deserialize from.
     * @return PortGroupNameMgmtConfig object.
     * @throws com.midokura.midolman.state.ZkStateSerializationException
     *             Serialization error.
     */
    public PortGroupNameMgmtConfig deserializeName(byte[] data)
            throws ZkStateSerializationException {
        try {
            return nameSerializer.bytesToObj(data, PortGroupNameMgmtConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize PortGroupNameMgmtConfig.", e,
                    PortGroupNameMgmtConfig.class);
        }
    }

    /**
     * Serialize PortGroupMgmtConfig object.
     *
     * @param config
     *            PortGroupMgmtConfig object.
     * @return byte array.
     * @throws com.midokura.midolman.state.ZkStateSerializationException
     *             Serialization error.
     */
    public byte[] serialize(PortGroupMgmtConfig config)
            throws ZkStateSerializationException {
        try {
            return serializer.objToBytes(config);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize PortGroupMgmtConfig", e,
                    PortGroupMgmtConfig.class);
        }
    }

    /**
     * Serialize PortGroupNameMgmtConfig object.
     *
     * @param config
     *            PortGroupNameMgmtConfig object.
     * @return byte array.
     * @throws com.midokura.midolman.state.ZkStateSerializationException
     *             Serialization error.
     */
    public byte[] serialize(PortGroupNameMgmtConfig config)
            throws ZkStateSerializationException {
        try {
            return nameSerializer.objToBytes(config);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize PortGroupNameMgmtConfig", e,
                    PortGroupNameMgmtConfig.class);
        }
    }
}
