/*
 * @(#)RouterLinkDaoAdapter        1.6 12/1/10
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.RouterLinkDao;
import com.midokura.midolman.mgmt.data.dto.LogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.PeerRouterLink;
import com.midokura.midolman.mgmt.data.dto.config.PeerRouterConfig;
import com.midokura.midolman.mgmt.data.zookeeper.op.RouterOpService;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.util.ShortUUID;

/**
 * Data access class for Router linking.
 *
 * @version 1.6 10 Jan 2012
 * @author Ryu Ishimoto
 */
public class RouterLinkDaoAdapter implements RouterLinkDao {

    private final static Logger log = LoggerFactory
            .getLogger(RouterLinkDaoAdapter.class);
    private final RouterZkDao zkDao;
    private final RouterOpService opService;

    /**
     * Constructor
     *
     * @param zkDao
     *            RouterZkDao object.
     * @param opService
     *            RouterOpService object
     */
    public RouterLinkDaoAdapter(RouterZkDao zkDao, RouterOpService opService) {
        this.zkDao = zkDao;
        this.opService = opService;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.RouterLinkDao#create(com.midokura
     * .midolman.mgmt.data.dto.LogicalRouterPort)
     */
    @Override
    public PeerRouterLink create(LogicalRouterPort port)
            throws StateAccessException {
        log.debug("RouterLinkDaoAdapter.create entered: port={}", port);

        if (port.getDeviceId() == null || port.getPeerRouterId() == null) {
            throw new IllegalArgumentException(
                    "Device Id and peer router ID must be set: deviceId="
                            + port.getDeviceId() + ", peerId="
                            + port.getPeerRouterId());
        }

        // Check that they are not currently linked.
        if (zkDao.routerLinkExists(port.getDeviceId(), port.getPeerRouterId())) {
            throw new IllegalArgumentException(
                    "Invalid connection.  The router ports are already connected.");
        }

        UUID portId = ShortUUID.generate32BitUUID();
        UUID peerPortId = ShortUUID.generate32BitUUID();
        port.setId(portId);
        port.setPeerId(peerPortId);
        List<Op> ops = opService.buildLink(port.getId(), port.toConfig(),
                port.getPeerId(), port.toPeerConfig());
        zkDao.multi(ops);
        PeerRouterLink link = port.toPeerRouterLink();

        log.debug("RouterLinkDaoAdapter.create exiting: link={}", link);
        return link;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.RouterLinkDao#delete(java.util.UUID,
     * java.util.UUID)
     */
    @Override
    public void delete(UUID routerId, UUID peerRouterId)
            throws StateAccessException {
        log.debug("RouterLinkDaoAdapter.delete entered: routerId=" + routerId
                + ", peerRouterId=" + peerRouterId);

        if (!zkDao.routerLinkExists(routerId, peerRouterId)) {
            throw new IllegalArgumentException(
                    "Invalid operation.  The router ports are not connected.");
        }

        List<Op> ops = opService.buildUnlink(routerId, peerRouterId);
        zkDao.multi(ops);

        log.debug("RouterLinkDaoAdapter.delete exiting.");
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.RouterLinkDao#get(java.util.UUID,
     * java.util.UUID)
     */
    @Override
    public PeerRouterLink get(UUID routerId, UUID peerRouterId)
            throws StateAccessException {
        log.debug("RouterLinkDaoAdapter.get entered: routerId=" + routerId
                + ", peerRouterId=" + peerRouterId);

        PeerRouterConfig config = zkDao.getRouterLinkData(routerId,
                peerRouterId);
        PeerRouterLink link = new PeerRouterLink(config, routerId, peerRouterId);

        log.debug("RouterLinkDaoAdapter.get exiting: link={}", link);
        return link;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.RouterLinkDao#list(java.util.UUID)
     */
    @Override
    public List<PeerRouterLink> list(UUID routerId) throws StateAccessException {
        log.debug("RouterLinkDaoAdapter.list entered: routerId={}", routerId);

        List<PeerRouterLink> links = new ArrayList<PeerRouterLink>();
        Set<String> peerIds = zkDao.getPeerRouterIds(routerId);
        for (String peerId : peerIds) {
            links.add(get(routerId, UUID.fromString(peerId)));
        }

        log.debug("RouterLinkDaoAdapter.list exiting: links count={}", links.size());
        return links;
    }
}
