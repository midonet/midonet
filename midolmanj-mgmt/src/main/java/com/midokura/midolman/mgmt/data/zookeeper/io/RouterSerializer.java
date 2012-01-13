/*
 * @(#)RouterSerializer        1.6 11/12/25
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.io;

import java.io.IOException;

import com.midokura.midolman.mgmt.data.dto.config.PeerRouterConfig;
import com.midokura.midolman.mgmt.data.dto.config.RouterMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.RouterNameMgmtConfig;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.util.Serializer;

/**
 * ZK Router serializer class.
 *
 * @version 1.6 25 Dec 2011
 * @author Ryu Ishimoto
 */
public class RouterSerializer {

    private final Serializer<RouterMgmtConfig> serializer;
    private final Serializer<RouterNameMgmtConfig> nameSerializer;
    private final Serializer<PeerRouterConfig> peerSerializer;

    /**
     * Constructor.
     *
     * @param serializer
     *            RouterMgmtConfig serializer.
     * @param nameSerializer
     *            RouterNameMgmtConfig serializer.
     * @param peerSerializer
     *            PeerRouterConfig serializer.
     */
    public RouterSerializer(Serializer<RouterMgmtConfig> serializer,
            Serializer<RouterNameMgmtConfig> nameSerializer,
            Serializer<PeerRouterConfig> peerSerializer) {
        this.serializer = serializer;
        this.nameSerializer = nameSerializer;
        this.peerSerializer = peerSerializer;
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
     * Deserialize PeerRouterConfig object.
     *
     * @param data
     *            Byte array to deserialize from.
     * @return PeerRouterConfig object.
     * @throws ZkStateSerializationException
     *             Serialization error.
     */
    public PeerRouterConfig deserializePeer(byte[] data)
            throws ZkStateSerializationException {
        try {
            return peerSerializer.bytesToObj(data, PeerRouterConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize PeerRouterConfig.", e,
                    PeerRouterConfig.class);
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
     * Serialize PeerRouterConfig object.
     *
     * @param config
     *            PeerRouterConfig object.
     * @return byte array.
     * @throws ZkStateSerializationException
     *             Serialization error.
     */
    public byte[] serialize(PeerRouterConfig config)
            throws ZkStateSerializationException {
        try {
            return peerSerializer.objToBytes(config);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize PeerRouterConfig", e,
                    PeerRouterConfig.class);
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
