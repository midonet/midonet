/*
 * @(#)ChainZkManagerProxy        1.6 19/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.state;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.layer3.Router;
import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.state.ChainZkManager.ChainConfig;

public class ChainZkManagerProxy extends ZkMgmtManager {

    public static class ChainMgmtConfig {

        public ChainMgmtConfig() {
            super();
        }

        public ChainMgmtConfig(String table) {
            super();
            this.table = table;
        }

        public String table;
    }

    public final static String NAT_TABLE = "nat";
    private static String[] builtInChainNames = { Router.POST_ROUTING,
            Router.PRE_ROUTING };
    private static String[] builtInTableNames = { NAT_TABLE };

    private ChainZkManager zkManager = null;
    private final static Logger log = LoggerFactory
            .getLogger(ChainZkManagerProxy.class);

    public ChainZkManagerProxy(ZooKeeper zk, String basePath,
            String mgmtBasePath) {
        super(zk, basePath, mgmtBasePath);
        zkManager = new ChainZkManager(zk, basePath);
    }

    public static boolean isBuiltInChainName(String name) {
        return Arrays.asList(builtInChainNames).contains(name);
    }

    public static boolean isBuiltInTableName(String name) {
        return Arrays.asList(builtInTableNames).contains(name);
    }

    public List<Op> prepareRouterInit(UUID routerId)
            throws ZkStateSerializationException, StateAccessException {
        List<Op> ops = new ArrayList<Op>();

        UUID preRoutingId = UUID.randomUUID();
        UUID postRoutingId = UUID.randomUUID();

        String routerTablesPath = mgmtPathManager.getRouterTablesPath(routerId);
        String routerNatTablePath = mgmtPathManager.getRouterTablePath(
                routerId, NAT_TABLE);
        String routerNatTableChainsPath = mgmtPathManager
                .getRouterTableChainsPath(routerId, NAT_TABLE);
        String preRoutingRouterTableChainPath = mgmtPathManager
                .getRouterTableChainPath(routerId, NAT_TABLE, preRoutingId);
        String postRoutingRouterTableChainPath = mgmtPathManager
                .getRouterTableChainPath(routerId, NAT_TABLE, postRoutingId);
        String preRoutingChainPath = mgmtPathManager.getChainPath(preRoutingId);
        String postRoutingChainPath = mgmtPathManager
                .getChainPath(postRoutingId);

        ZkNodeEntry<UUID, ChainConfig> preRoutingEntry = new ZkNodeEntry<UUID, ChainConfig>(
                preRoutingId, new ChainConfig(Router.PRE_ROUTING, routerId));
        ZkNodeEntry<UUID, ChainConfig> postRoutingEntry = new ZkNodeEntry<UUID, ChainConfig>(
                postRoutingId, new ChainConfig(Router.POST_ROUTING, routerId));
        ChainMgmtConfig preRoutingMgmtConfig = new ChainMgmtConfig(NAT_TABLE);
        ChainMgmtConfig postRoutingMgmtConfig = new ChainMgmtConfig(NAT_TABLE);

        log.debug("Preparing to create: " + routerTablesPath);
        ops.add(Op.create(routerTablesPath, null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));
        log.debug("Preparing to create: " + routerNatTablePath);
        ops.add(Op.create(routerNatTablePath, null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));
        log.debug("Preparing to create: " + routerNatTableChainsPath);
        ops.add(Op.create(routerNatTableChainsPath, null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));

        log.debug("Preparing to create: " + preRoutingRouterTableChainPath);
        ops.add(Op.create(preRoutingRouterTableChainPath, null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        log.debug("Preparing to create: " + postRoutingRouterTableChainPath);
        ops.add(Op.create(postRoutingRouterTableChainPath, null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        // Add chains entry
        log.debug("Preparing to create: " + preRoutingChainPath);
        try {
            ops.add(Op.create(preRoutingChainPath,
                    serialize(preRoutingMgmtConfig), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize prerouting ChainMgmtConfig", e,
                    ChainMgmtConfig.class);
        }

        log.debug("Preparing to create: " + postRoutingChainPath);
        try {
            ops.add(Op.create(postRoutingChainPath,
                    serialize(postRoutingMgmtConfig), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize postrouting ChainMgmtConfig", e,
                    ChainMgmtConfig.class);
        }

        // Create Pre-routing and post-routing in Midolman
        log.debug("Preparing Midolman post-routing/pre-routing chains");
        ops.addAll(zkManager.prepareChainCreate(preRoutingEntry));
        ops.addAll(zkManager.prepareChainCreate(postRoutingEntry));

        return ops;
    }

    public List<Op> prepareCreate(Chain chain)
            throws ZkStateSerializationException, StateAccessException {
        // Don't allow creation of any built-in chain names
        if (isBuiltInChainName(chain.getName())) {
            throw new IllegalArgumentException(
                    "Cannot create a bulit-in chain name " + chain.getName());
        }

        if (!isBuiltInTableName(chain.getTable())) {
            throw new IllegalArgumentException("Unknown table name: "
                    + chain.getTable());
        }

        List<Op> ops = new ArrayList<Op>();
        String chainPath = mgmtPathManager.getChainPath(chain.getId());
        String routerTableChainPath = mgmtPathManager.getRouterTableChainPath(
                chain.getRouterId(), chain.getTable(), chain.getId());
        ZkNodeEntry<UUID, ChainConfig> entry = new ZkNodeEntry<UUID, ChainConfig>(
                chain.getId(), chain.toConfig());
        ChainMgmtConfig mgmtConfig = new ChainMgmtConfig(chain.getTable());

        // Create under router table.
        log.debug("Preparing to create: " + routerTableChainPath);
        ops.add(Op.create(routerTableChainPath, null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));

        // Create one under top level.
        log.debug("Preparing to create: " + chainPath);
        try {
            ops.add(Op.create(chainPath, serialize(mgmtConfig),
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize postrouting ChainMgmtConfig", e,
                    ChainMgmtConfig.class);
        }

        log.debug("Preparing Midolman chain");
        ops.addAll(zkManager.prepareChainCreate(entry));

        return ops;
    }

    public List<Op> prepareDelete(UUID id)
            throws ZkStateSerializationException, StateAccessException {
        return prepareDelete(get(id));
    }

    public List<Op> prepareDelete(Chain chain)
            throws ZkStateSerializationException, StateAccessException {
        // Cannot delete built-in chains
        if (isBuiltInChainName(chain.getName())) {
            throw new IllegalArgumentException(
                    "Cannot delete a bulit-in chain name " + chain.getName());
        }

        if (!isBuiltInTableName(chain.getTable())) {
            throw new IllegalArgumentException("Unknown table name: "
                    + chain.getTable());
        }

        List<Op> ops = new ArrayList<Op>();

        // Construct paths
        String chainPath = mgmtPathManager.getChainPath(chain.getId());
        String routerTableChainPath = mgmtPathManager.getRouterTableChainPath(
                chain.getRouterId(), chain.getTable(), chain.getId());

        // Delete Midolman data.
        ZkNodeEntry<UUID, ChainConfig> entry = new ZkNodeEntry<UUID, ChainConfig>(
                chain.getId(), chain.toConfig());
        log.debug("Preparing deletion of Midolman chain");
        ops.addAll(zkManager.prepareChainDelete(entry));

        // Delete the top level chain entry
        log.debug("Preparing to delete: " + chainPath);
        ops.add(Op.delete(chainPath, -1));

        // Delete from the table.
        log.debug("Preparing to delete: " + routerTableChainPath);
        ops.add(Op.delete(routerTableChainPath, -1));

        return ops;
    }

    public UUID create(Chain chain) throws StateAccessException,
            ZkStateSerializationException {
        UUID chainId = UUID.randomUUID();
        chain.setId(chainId);
        multi(prepareCreate(chain));
        return chainId;
    }

    public List<Chain> listTableChains(UUID routerId, String table)
            throws StateAccessException, ZkStateSerializationException {
        List<Chain> result = new ArrayList<Chain>();
        Set<String> ids = getChildren(mgmtPathManager.getRouterTableChainsPath(
                routerId, table));
        for (String id : ids) {
            // For now, get each one.
            result.add(get(UUID.fromString(id)));
        }
        return result;
    }

    public List<Chain> listNatChains(UUID routerId)
            throws StateAccessException, ZkStateSerializationException {
        return listTableChains(routerId, NAT_TABLE);
    }

    public List<Chain> list(UUID routerId) throws StateAccessException,
            ZkStateSerializationException {
        return listNatChains(routerId);
    }

    public Chain get(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        ZkNodeEntry<UUID, ChainConfig> config = zkManager.get(id);
        byte[] data = get(mgmtPathManager.getChainPath(id));
        ChainMgmtConfig mgmtConfig = null;
        try {
            mgmtConfig = deserialize(data, ChainMgmtConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize ChainMgmtConfig", e,
                    ChainMgmtConfig.class);
        }
        Chain c = new Chain();
        c.setId(config.key);
        c.setRouterId(config.value.routerId);
        c.setName(config.value.name);
        c.setTable(mgmtConfig.table);
        return c;
    }

    public void delete(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        multi(prepareDelete(id));
    }
}
