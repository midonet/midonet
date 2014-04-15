/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.state.zkManagers;

import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.ZkManager;
import org.midonet.packets.IPv4Addr;

import java.util.List;
import org.apache.zookeeper.Op;

import static java.util.Arrays.asList;

public class VtepZkManager
        extends AbstractZkManager<IPv4Addr, VtepZkManager.VtepConfig> {

    public static class VtepConfig {
        public int mgmtPort;
    }

    public VtepZkManager(ZkManager zk, PathBuilder paths,
                         Serializer serializer) {
        super(zk, paths, serializer);
    }

    @Override
    protected String getConfigPath(IPv4Addr key) {
        return paths.getVtepPath(key);
    }

    @Override
    protected Class<VtepConfig> getConfigClass() {
        return VtepConfig.class;
    }

    public List<Op> prepareCreate(IPv4Addr ipAddr, VtepConfig vtepConfig)
            throws SerializationException {
        return asList(simpleCreateOp(ipAddr, vtepConfig));
    }
}
