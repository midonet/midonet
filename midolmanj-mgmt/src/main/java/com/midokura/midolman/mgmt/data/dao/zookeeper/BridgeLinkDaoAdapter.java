/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.BridgeLinkDao;
import com.midokura.midolman.mgmt.data.dto.BridgeRouterLink;
import com.midokura.midolman.mgmt.data.dto.config.PeerRouterConfig;
import com.midokura.midolman.mgmt.data.zookeeper.op.BridgeOpService;
import com.midokura.midolman.mgmt.data.zookeeper.op.RouterOpService;
import com.midokura.midolman.state.StateAccessException;

public class BridgeLinkDaoAdapter implements BridgeLinkDao {
    private final static Logger log = LoggerFactory
            .getLogger(BridgeLinkDaoAdapter.class);
    private final BridgeZkDao zkDao;
    private final BridgeOpService opService;

    public BridgeLinkDaoAdapter(BridgeZkDao bridgeZkDao,
            BridgeOpService bridgeOpService) {
        this.zkDao = bridgeZkDao;
        this.opService = bridgeOpService;
    }

    @Override
    public BridgeRouterLink getRouterLink(UUID bridgeId, UUID routerId) throws StateAccessException {
        PeerRouterConfig config =
                zkDao.getBridgeRouterLinkData(bridgeId, routerId);
        return new BridgeRouterLink(
                config.peerPortId, config.portId, routerId, bridgeId);
    }

    @Override
    public List<BridgeRouterLink> listRouterLinks(UUID bridgeId) throws StateAccessException {
        List<BridgeRouterLink> links = new ArrayList<BridgeRouterLink>();
        Set<String> routerIds = zkDao.getRouterIds(bridgeId);
        for (String routerId : routerIds) {
            links.add(getRouterLink(bridgeId, UUID.fromString(routerId)));
        }
        return links;
    }
}
