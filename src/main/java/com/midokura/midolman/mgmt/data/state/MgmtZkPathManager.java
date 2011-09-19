/*
 * @(#)MgmtZkPathManager        1.6 19/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.state;

import java.util.UUID;

import com.midokura.midolman.state.ZkPathManager;

/**
 * This class was created to have all state classes share the Zk path
 * information.
 * 
 * @version 1.6 19 Sept 2011
 * @author Ryu Ishimoto
 */
public class MgmtZkPathManager extends ZkPathManager {

	/**
	 * Constructor.
	 * 
	 * @param basePath
	 *            Base path of Zk.
	 */
	public MgmtZkPathManager(String basePath) {
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
}
