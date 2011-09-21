/*
 * @(#)ZkMgmtPathManager        1.6 19/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.state;

import java.util.UUID;

import com.midokura.midolman.state.ZkBasePathManager;

/**
 * This class was created to have all state classes share the Zk path
 * information.
 * 
 * @version 1.6 19 Sept 2011
 * @author Ryu Ishimoto
 */
public class ZkMgmtPathManager extends ZkBasePathManager {

	/**
	 * Constructor.
	 * 
	 * @param basePath
	 *            Base path of Zk.
	 */
	public ZkMgmtPathManager(String basePath) {
		super(basePath);
	}

	/**
	 * Get VIF path.
	 * 
	 * @return /vifs
	 */
	public String getVifsPath() {
		return new StringBuilder(basePath).append("/vifs").toString();
	}

	/**
	 * Get VIF path.
	 * 
	 * @return /vifs/vifId
	 */
	public String getVifPath(UUID vifId) {
		return new StringBuilder(getVifsPath()).append("/").append(vifId)
				.toString();
	}

	/**
	 * Get ZK tenant path.
	 * 
	 * @return /tenants
	 */
	public String getTenantsPath() {
		return new StringBuilder(basePath).append("/tenants").toString();
	}

	/**
	 * Get ZK tenant path.
	 * 
	 * @param id
	 *            Tenant UUID
	 * @return /tenants/tenantId
	 */
	public String getTenantPath(UUID id) {
		return new StringBuilder(getTenantsPath()).append("/").append(id)
				.toString();
	}

	/**
	 * Get ZK tenant router path.
	 * 
	 * @param tenantId
	 *            Tenant UUID
	 * @return /tenants/tenantId/routers
	 */
	public String getTenantRoutersPath(UUID tenantId) {
		return new StringBuilder(getTenantPath(tenantId)).append("/routers")
				.toString();
	}

	/**
	 * Get ZK tenant router path.
	 * 
	 * @param tenantId
	 *            Tenant UUID
	 * @param routerId
	 *            Router UUID
	 * @return /tenants/tenantId/routers/routerId
	 */
	public String getTenantRouterPath(UUID tenantId, UUID routerId) {
		return new StringBuilder(getTenantRoutersPath(tenantId)).append("/")
				.append(routerId).toString();
	}

	/**
	 * Get ZK tenant bridge path.
	 * 
	 * @param tenantId
	 *            Tenant UUID
	 * @return /tenants/tenantId/bridges
	 */
	public String getTenantBridgesPath(UUID tenantId) {
		return new StringBuilder(getTenantPath(tenantId)).append("/bridges")
				.toString();
	}

	/**
	 * Get ZK tenant bridge path.
	 * 
	 * @param tenantId
	 *            Tenant UUID
	 * @param routerId
	 *            Bridge UUID
	 * @return /tenants/tenantId/bridges/bridgeId
	 */
	public String getTenantBridgePath(UUID tenantId, UUID bridgeId) {
		return new StringBuilder(getTenantBridgesPath(tenantId)).append("/")
				.append(bridgeId).toString();
	}

    /**
     * Get ZK router path.
     * 
     * @return /routers
     */
    public String getRoutersPath() {
        return new StringBuilder(basePath).append("/routers").toString();
    }

    /**
     * Get ZK router path.
     * 
     * @param id
     *            Router UUID
     * @return /routers/routerId
     */
    public String getRouterPath(UUID id) {
        return new StringBuilder(getRoutersPath()).append("/").append(id)
                .toString();
    }
	
	/**
	 * Get ZK router peer router path.
	 * 
	 * @param routerId
	 *            Router UUID
	 * @return /routers/routerId/routers
	 */
	public String getRouterRoutersPath(UUID routerId) {
		return new StringBuilder(getRouterPath(routerId)).append("/routers")
				.toString();
	}

	/**
	 * Get ZK router peer router path.
	 * 
	 * @param routerId
	 *            Router UUID
	 * @return /routers/routerId/routers/routerId
	 */
	public String getRouterRouterPath(UUID routerId, UUID peerRouterId) {
		return new StringBuilder(getRouterRoutersPath(routerId)).append("/")
				.append(peerRouterId).toString();
	}
	
    /**
     * Get ZK port path.
     * 
     * @return /ports
     */
    public String getPortsPath() {
        return new StringBuilder(basePath).append("/ports").toString();
    }

    /**
     * Get ZK port path.
     * 
     * @param id
     *            Port ID.
     * @return /ports/portId
     */
    public String getPortPath(UUID id) {
        return new StringBuilder(getPortsPath()).append("/").append(id)
                .toString();
    }
    
    /**
     * Get ZK bridges path.
     * 
     * @return /birdges
     */
    public String getBridgesPath() {
        return new StringBuilder(basePath).append("/bridges").toString();
    }

    /**
     * Get ZK bridge path.
     * 
     * @param id
     *            Bridge UUID
     * @return /bridges/bridgeId
     */
    public String getBridgePath(UUID id) {
        return new StringBuilder(getBridgesPath()).append("/").append(id)
                .toString();
    }
}
