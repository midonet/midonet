/*
 * @(#)ChainZkManagerProxy        1.6 19/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.layer3.Router;
import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.Directory;
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

    public static class ChainNameMgmtConfig {
        public ChainNameMgmtConfig() {
            super();
        }

        public ChainNameMgmtConfig(UUID id) {
            super();
            this.id = id;
        }

        public UUID id;
    }

    public final static String NAT_TABLE = "nat";
    private static String[] builtInChainNames = { Router.POST_ROUTING,
            Router.PRE_ROUTING };
    private static String[] builtInTableNames = { NAT_TABLE };

    private ChainZkManager zkManager = null;
    private final static Logger log = LoggerFactory
            .getLogger(ChainZkManagerProxy.class);

    public ChainZkManagerProxy(Directory zk, String basePath,
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

        // Create router tables path
        String routerTablesPath = mgmtPathManager.getRouterTablesPath(routerId);
        log.debug("Preparing to create: " + routerTablesPath);
        ops.add(Op.create(routerTablesPath, null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));

        // Create router NAT table path
        String routerNatTablePath = mgmtPathManager.getRouterTablePath(
                routerId, NAT_TABLE);
        log.debug("Preparing to create: " + routerNatTablePath);
        ops.add(Op.create(routerNatTablePath, null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));

        // Create router NAT table chains path.
        String routerNatTableChainsPath = mgmtPathManager
                .getRouterTableChainsPath(routerId, NAT_TABLE);
        log.debug("Preparing to create: " + routerNatTableChainsPath);
        ops.add(Op.create(routerNatTableChainsPath, null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));

        // Create router NAT table chain names path.
        String routerNatTableChainNamesPath = mgmtPathManager
                .getRouterTableChainNamesPath(routerId, NAT_TABLE);
        log.debug("Preparing to create: " + routerNatTableChainNamesPath);
        ops.add(Op.create(routerNatTableChainNamesPath, null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        // Create Router NAT table pre-routing chain
        String preRoutingRouterTableChainPath = mgmtPathManager
                .getRouterTableChainPath(routerId, NAT_TABLE, preRoutingId);
        log.debug("Preparing to create: " + preRoutingRouterTableChainPath);
        ops.add(Op.create(preRoutingRouterTableChainPath, null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        // Create router NAT table post-routing chain
        String postRoutingRouterTableChainPath = mgmtPathManager
                .getRouterTableChainPath(routerId, NAT_TABLE, postRoutingId);
        log.debug("Preparing to create: " + postRoutingRouterTableChainPath);
        ops.add(Op.create(postRoutingRouterTableChainPath, null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        // Create Router NAT table pre-routing chain
        String preRoutingChainNamePath = mgmtPathManager
                .getRouterTableChainNamePath(routerId, NAT_TABLE,
                        Router.PRE_ROUTING);
        log.debug("Preparing to create: " + preRoutingChainNamePath);
        try {
            ops.add(Op.create(preRoutingChainNamePath,
                    serialize(new ChainNameMgmtConfig(preRoutingId)),
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize prerouting ChainNameMgmtConfig", e,
                    ChainNameMgmtConfig.class);
        }

        // Create router NAT table post-routing chain
        String postRoutingChainNamePath = mgmtPathManager
                .getRouterTableChainNamePath(routerId, NAT_TABLE,
                        Router.POST_ROUTING);
        log.debug("Preparing to create: " + postRoutingChainNamePath);
        try {
            ops.add(Op.create(postRoutingChainNamePath,
                    serialize(new ChainNameMgmtConfig(postRoutingId)),
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize posdrouting ChainNameMgmtConfig", e,
                    ChainNameMgmtConfig.class);
        }

        // Add a pre-routing chain entry
        String preRoutingChainPath = mgmtPathManager.getChainPath(preRoutingId);
        log.debug("Preparing to create: " + preRoutingChainPath);
        ChainMgmtConfig preRoutingMgmtConfig = new ChainMgmtConfig(NAT_TABLE);
        try {
            ops.add(Op.create(preRoutingChainPath,
                    serialize(preRoutingMgmtConfig), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize prerouting ChainMgmtConfig", e,
                    ChainMgmtConfig.class);
        }

        // Add a post-routing chain entry.
        String postRoutingChainPath = mgmtPathManager
                .getChainPath(postRoutingId);
        log.debug("Preparing to create: " + postRoutingChainPath);
        ChainMgmtConfig postRoutingMgmtConfig = new ChainMgmtConfig(NAT_TABLE);
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
        ZkNodeEntry<UUID, ChainConfig> preRoutingEntry = new ZkNodeEntry<UUID, ChainConfig>(
                preRoutingId, new ChainConfig(Router.PRE_ROUTING, routerId));
        ZkNodeEntry<UUID, ChainConfig> postRoutingEntry = new ZkNodeEntry<UUID, ChainConfig>(
                postRoutingId, new ChainConfig(Router.POST_ROUTING, routerId));
        log.debug("Preparing Midolman post-routing/pre-routing chains");
        ops.addAll(zkManager.prepareChainCreate(preRoutingEntry));
        ops.addAll(zkManager.prepareChainCreate(postRoutingEntry));

        return ops;
    }

    public List<Op> prepareRouterDelete(UUID routerId)
            throws ZkStateSerializationException, StateAccessException {
        return prepareRouterDelete(routerId, true);
    }

    public List<Op> prepareRouterDelete(UUID routerId, boolean cascade)
            throws StateAccessException, ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();

        // Get all the chains and delete. NAT-only for now.
        String natChainsPath = mgmtPathManager.getRouterTableChainsPath(
                routerId, NAT_TABLE);
        Set<String> chainIds = getChildren(natChainsPath);
        for (String chainId : chainIds) {
            ops.addAll(prepareDelete(UUID.fromString(chainId), cascade));
        }

        // Remove the chain-names directory
        String namesPath = mgmtPathManager.getRouterTableChainNamesPath(
                routerId, NAT_TABLE);
        log.debug("Preparing to delete: " + namesPath);
        ops.add(Op.delete(namesPath, -1));

        // Remove the chains directory
        String chainsPath = mgmtPathManager.getRouterTableChainsPath(routerId,
                NAT_TABLE);
        log.debug("Preparing to delete: " + chainsPath);
        ops.add(Op.delete(chainsPath, -1));

        // Remove all the tables
        String tablesPath = mgmtPathManager.getRouterTablesPath(routerId);
        Set<String> tables = getChildren(tablesPath);
        for (String table : tables) {
            String tablePath = mgmtPathManager.getRouterTablePath(routerId,
                    table);
            log.debug("Preparing to delete: " + tablePath);
            ops.add(Op.delete(tablePath, -1));
        }

        // Remove the parent table directory
        log.debug("Preparing to delete: " + tablesPath);
        ops.add(Op.delete(tablesPath, -1));

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

        // Make sure that no chain with the same name exists.
        String namePath = mgmtPathManager.getRouterTableChainNamePath(chain
                .getRouterId(), chain.getTable(), chain.getName());
        if (exists(namePath)) {
            throw new IllegalArgumentException(
                    "A chain with the same name already exists for this table.");
        }

        List<Op> ops = new ArrayList<Op>();

        // Create under router table.
        String routerTableChainPath = mgmtPathManager.getRouterTableChainPath(
                chain.getRouterId(), chain.getTable(), chain.getId());
        log.debug("Preparing to create: " + routerTableChainPath);
        ops.add(Op.create(routerTableChainPath, null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));

        // Create a name index for this chain.
        log.debug("Preparing to create: " + namePath);
        try {
            ops.add(Op.create(namePath, serialize(chain.toNameMgmtConfig()),
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize ChainNameMgmtConfig", e,
                    ChainNameMgmtConfig.class);
        }

        // Create one under top level.
        String chainPath = mgmtPathManager.getChainPath(chain.getId());
        log.debug("Preparing to create: " + chainPath);
        try {
            ops.add(Op.create(chainPath, serialize(chain.toMgmtConfig()),
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize postrouting ChainMgmtConfig", e,
                    ChainMgmtConfig.class);
        }

        ZkNodeEntry<UUID, ChainConfig> entry = new ZkNodeEntry<UUID, ChainConfig>(
                chain.getId(), chain.toConfig());
        log.debug("Preparing Midolman chain");
        ops.addAll(zkManager.prepareChainCreate(entry));

        return ops;
    }

    public List<Op> prepareDelete(UUID id)
            throws ZkStateSerializationException, StateAccessException {
        return prepareDelete(id, true);
    }

    public List<Op> prepareDelete(UUID id, boolean cascade)
            throws ZkStateSerializationException, StateAccessException {
        return prepareDelete(get(id), cascade);
    }

    public List<Op> prepareDelete(Chain chain)
            throws ZkStateSerializationException, StateAccessException {
        return prepareDelete(chain, true);
    }

    public List<Op> prepareDelete(Chain chain, boolean cascade)
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

        // Delete Midolman data if cascading.
        if (cascade) {
            ZkNodeEntry<UUID, ChainConfig> entry = new ZkNodeEntry<UUID, ChainConfig>(
                    chain.getId(), chain.toConfig());
            log.debug("Preparing deletion of Midolman chain");
            ops.addAll(zkManager.prepareChainDelete(entry));
        }

        // Delete the top level chain entry
        String chainPath = mgmtPathManager.getChainPath(chain.getId());
        log.debug("Preparing to delete: " + chainPath);
        ops.add(Op.delete(chainPath, -1));

        // Delete the name index.
        String routerTableChainNamePath = mgmtPathManager
                .getRouterTableChainNamePath(chain.getRouterId(), chain
                        .getTable(), chain.getName());
        log.debug("Preparing to delete: " + routerTableChainNamePath);
        ops.add(Op.delete(routerTableChainNamePath, -1));

        // Delete from the table.
        String routerTableChainPath = mgmtPathManager.getRouterTableChainPath(
                chain.getRouterId(), chain.getTable(), chain.getId());
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

    public Chain get(UUID routerId, String table, String name)
            throws StateAccessException, ZkStateSerializationException {
        String namePath = mgmtPathManager.getRouterTableChainNamePath(routerId,
                table.toLowerCase(), name.toLowerCase());
        byte[] data = get(namePath);
        ChainNameMgmtConfig nameConfig = null;
        try {
            nameConfig = deserialize(data, ChainNameMgmtConfig.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not serialize ChainNameMgmtConfig", e,
                    ChainMgmtConfig.class);
        }

        // Now get the data
        return get(nameConfig.id);
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
        return Chain.createChain(id, config.value, mgmtConfig);
    }

    public void delete(UUID id) throws StateAccessException,
            ZkStateSerializationException {
        multi(prepareDelete(id));
    }
}
