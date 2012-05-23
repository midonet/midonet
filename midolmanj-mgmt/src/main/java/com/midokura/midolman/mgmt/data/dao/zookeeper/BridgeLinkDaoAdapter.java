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
import com.midokura.midolman.state.StateAccessException;

public class BridgeLinkDaoAdapter implements BridgeLinkDao {
	private final static Logger log = LoggerFactory
			.getLogger(BridgeLinkDaoAdapter.class);
	private final BridgeZkDao zkDao;

	public BridgeLinkDaoAdapter(BridgeZkDao bridgeZkDao) {
		this.zkDao = bridgeZkDao;
	}

	@Override
	public BridgeRouterLink getRouterLink(UUID bridgeId, UUID routerId)
			throws StateAccessException {
		log.debug("BridgeLinkDaoAdapter.getRouterLink: entered with "
				+ "bridgeId=" + bridgeId + ", routerId=" + routerId);
		PeerRouterConfig config = zkDao.getBridgeRouterLinkData(bridgeId,
				routerId);
		return new BridgeRouterLink(config.peerPortId, config.portId, routerId,
				bridgeId);
	}

	@Override
	public List<BridgeRouterLink> listRouterLinks(UUID bridgeId)
			throws StateAccessException {
		log.debug("BridgeLinkDaoAdapter.listRouterLinks: entered with "
				+ "bridgeId=" + bridgeId);
		List<BridgeRouterLink> links = new ArrayList<BridgeRouterLink>();
		Set<String> routerIds = zkDao.getRouterIds(bridgeId);
		for (String routerId : routerIds) {
			links.add(getRouterLink(bridgeId, UUID.fromString(routerId)));
		}
		return links;
	}
}
