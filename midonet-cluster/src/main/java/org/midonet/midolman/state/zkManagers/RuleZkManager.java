/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman.state.zkManagers;

import java.util.AbstractMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.rules.Rule;
import org.midonet.midolman.rules.RuleList;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;

/**
 * This class was created to handle multiple ops feature in Zookeeper.
 */
public class RuleZkManager extends AbstractZkManager<UUID, Rule> {

    private final static Logger log = LoggerFactory
            .getLogger(RuleZkManager.class);

    ChainZkManager chainZkManager;
    /**
     * Constructor to set ZooKeeper and base path.
     *
     * @param zk
     *         Zk data access class
     * @param paths
     *         PathBuilder class to construct ZK paths
     * @param serializer
     *         ZK data serialization class
     */
    public RuleZkManager(ZkManager zk, PathBuilder paths,
                         Serializer serializer) {
        super(zk, paths, serializer);
        chainZkManager = new ChainZkManager(zk, paths, serializer);
    }

    @Override
    protected String getConfigPath(UUID id) {
        return paths.getRulePath(id);
    }

    @Override
    protected Class<Rule> getConfigClass() {
        return Rule.class;
    }

    /**
     * Gets a list of ZooKeeper rule nodes belonging to a chain with the given
     * ID.
     *
     * @param chainId The ID of the chain to find the rules of.
     * @return A list of rule IDs
     * @throws StateAccessException
     */
    public Map.Entry<RuleList, Integer> getRuleListWithVersion(UUID chainId,
            Runnable watcher) throws StateAccessException {
        String path = paths.getChainRulesPath(chainId);

        if (!zk.exists(path)) {
            // In this case we are creating the rule list along with this op
            return new AbstractMap.SimpleEntry<>(new RuleList(), -1);
        }

        Map.Entry<byte[], Integer> ruleIdsVersion = zk.getWithVersion(path,
            watcher);
        byte[] data = ruleIdsVersion.getKey();
        int version = ruleIdsVersion.getValue();

        // convert
        try {
            RuleList ruleList = serializer.deserialize(data, RuleList.class);
            return new AbstractMap.SimpleEntry<>(ruleList, version);
        } catch (SerializationException e) {
            log.error("Could not deserialize rule list {}", data, e);
            return null;
        }
    }

    public RuleList getRuleList(UUID chainId) throws StateAccessException {
        return getRuleListWithVersion(chainId, null).getKey();
    }

}
