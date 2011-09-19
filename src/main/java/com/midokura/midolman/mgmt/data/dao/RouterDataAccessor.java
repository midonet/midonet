/*
 * @(#)RouterDataAccessor        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.ZookeeperService;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.data.state.MgmtRouterZkManager;
import com.midokura.midolman.mgmt.data.state.MgmtRouterZkManager.RouterMgmtConfig;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.ZkNodeEntry;

/**
 * Data access class for router.
 * 
 * @version 1.6 05 Sept 2011
 * @author Ryu Ishimoto
 */
public class RouterDataAccessor extends DataAccessor {
	/*
	 * Implements CRUD operations on Router.
	 */

	/**
	 * Constructor
	 * 
	 * @param zkConn
	 *            Zookeeper connection string
	 */
	public RouterDataAccessor(String zkConn, int timeout, String rootPath,
			String mgmtRootPath) {
		super(zkConn, timeout, rootPath, mgmtRootPath);
	}

	private MgmtRouterZkManager getRouterZkManager() throws Exception {
		ZkConnection conn = ZookeeperService.getConnection(zkConn, zkTimeout);
		return new MgmtRouterZkManager(conn.getZooKeeper(), zkRoot, zkMgmtRoot);
	}

	private static RouterMgmtConfig convertToConfig(Router router) {
		return new RouterMgmtConfig(router.getTenantId(), router.getName());
	}

	private static Router convertToRouter(RouterMgmtConfig config) {
		Router router = new Router();
		router.setName(config.name);
		router.setTenantId(config.tenantId);
		return router;
	}

	private static Router convertToRouter(
			ZkNodeEntry<UUID, RouterMgmtConfig> entry) {
		Router r = convertToRouter(entry.value);
		r.setId(entry.key);
		return r;
	}

	public UUID create(Router router) throws Exception {
		return getRouterZkManager().create(convertToConfig(router));
	}

	/**
	 * Get a Router for the given ID.
	 * 
	 * @param id
	 *            Router ID to search.
	 * @return Router object with the given ID.
	 * @throws Exception
	 *             Error getting data to Zookeeper.
	 */
	public Router get(UUID id) throws Exception {
		// TODO: Throw NotFound exception here.
		return convertToRouter(getRouterZkManager().getMgmt(id));
	}

	/**
	 * Get a list of Routers for a tenant.
	 * 
	 * @param tenantId
	 *            UUID of tenant.
	 * @return A Set of Routers
	 * @throws Exception
	 *             Zookeeper(or any) error.
	 */
	public Router[] list(UUID tenantId) throws Exception {
		MgmtRouterZkManager manager = getRouterZkManager();
		List<Router> routers = new ArrayList<Router>();
		List<ZkNodeEntry<UUID, RouterMgmtConfig>> entries = manager
				.listMgmt(tenantId);
		for (ZkNodeEntry<UUID, RouterMgmtConfig> entry : entries) {
			routers.add(convertToRouter(entry));
		}
		return routers.toArray(new Router[routers.size()]);
	}

	/**
	 * Update Router entry in MidoNet ZK data storage.
	 * 
	 * @param router
	 *            Router object to update.
	 * @throws Exception
	 *             Error adding data to ZooKeeper.
	 */
	public void update(UUID id, Router router) throws Exception {
		MgmtRouterZkManager manager = getRouterZkManager();
		// Only allow an update of 'name'
		ZkNodeEntry<UUID, RouterMgmtConfig> entry = manager.getMgmt(id);
		// Just allow copy of the name.
		entry.value.name = router.getName();
		manager.update(entry);
	}

	public void delete(UUID id) throws Exception {
		MgmtRouterZkManager manager = getRouterZkManager();
		// TODO: catch NoNodeException if does not exist.
		manager.delete(id);
	}
}
