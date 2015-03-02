/*
 * Copyright 2015 Midokura SARL
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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.midonet.cluster.data.Rule.RuleIndexOutOfBoundsException;
import org.midonet.cluster.data.TraceRequest;
import org.midonet.midolman.rules.Rule;
import org.midonet.midolman.rules.RuleList;
import org.midonet.midolman.rules.Condition;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;

import static java.util.Arrays.asList;

/**
 * Class to manage the trace request ZooKeeper data.
 */
public class TraceRequestZkManager
    extends AbstractZkManager<UUID, TraceRequestZkManager.TraceRequestConfig> {

    private final static Logger log = LoggerFactory
        .getLogger(TraceRequestZkManager.class);

    public static class TraceRequestConfig extends BaseConfig {
        public TraceRequest.DeviceType deviceType;
        public UUID deviceId;
        public Condition condition;
        public UUID enabledRule;

        public TraceRequestConfig() {
            super();
        }

        public TraceRequestConfig(TraceRequest.DeviceType deviceType,
                                  UUID deviceId, Condition condition,
                                  UUID enabledRule) {
            super();
            this.deviceType = deviceType;
            this.deviceId = deviceId;
            this.condition = condition;
            this.enabledRule = enabledRule;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (other == null || !(other instanceof TraceRequestConfig)) {
                return false;
            }

            TraceRequestConfig that = (TraceRequestConfig)other;
            return deviceType == that.deviceType
                && Objects.equals(deviceId, that.deviceId)
                && Objects.equals(condition, that.condition)
                && Objects.equals(enabledRule, that.enabledRule);
        }

        @Override
        public int hashCode() {
            return Objects.hash(deviceType, deviceId, condition, enabledRule);
        }

        @Override
        public String toString() {
            return "TraceRequestConfig{deviceType=" + deviceType
                + ", deviceId=" + deviceId
                + ", condition=" + condition
                + ", enabledRule=" + enabledRule + "}";
        }
    }

    private RuleZkManager ruleZkManager;
    private ChainZkManager chainZkManager;

    public TraceRequestZkManager(ZkManager zk, PathBuilder paths,
                                 Serializer serializer) {
        super(zk, paths, serializer);
        ruleZkManager = new RuleZkManager(zk, paths, serializer);
        chainZkManager = new ChainZkManager(zk, paths, serializer);
    }

    @Override
    public Class<TraceRequestConfig> getConfigClass() {
        return TraceRequestConfig.class;
    }

    @Override
    public String getConfigPath(UUID id) {
        return paths.getTraceRequestPath(id);
    }

    public static String traceRequestChainName(UUID id) {
        return "TRACE_REQUEST_CHAIN_" + id.toString();
    }

    public List<Op> prepareCreate(UUID id, TraceRequestConfig config)
            throws SerializationException {
        return asList(simpleCreateOp(id, config));
    }

    public List<Op> prepareDisable(UUID id)
            throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<Op>();
        TraceRequestConfig config = get(id);
        if (config == null
            || config.enabledRule == null) {
            return ops;
        }
        UUID ruleId = config.enabledRule;
        Rule rule = ruleZkManager.get(ruleId);
        if (rule != null) {
            RuleList ruleList = ruleZkManager.getRuleList(rule.chainId);
            ChainZkManager.ChainConfig chain = chainZkManager.get(rule.chainId);
            if (ruleList.getRuleList().size() == 1
                && ruleList.getRuleList().get(0).equals(ruleId)
                && chain.name.equals(traceRequestChainName(id))) {
                ops.addAll(chainZkManager.prepareDelete(rule.chainId));
            } else {
                ops.addAll(ruleZkManager.prepareDelete(ruleId));
            }
        }
        config.enabledRule = null;
        ops.addAll(prepareUpdate(id, config));

        return ops;
    }

    public List<Op> prepareDelete(UUID id)
            throws StateAccessException, SerializationException {
        String path = paths.getTraceRequestPath(id);
        log.debug("Preparing to delete {}", path);
        List<Op> ops = new ArrayList<Op>();
        ops.addAll(prepareDisable(id));
        ops.add(Op.delete(path, -1));
        return ops;
    }

    public void deleteForDevice(UUID deviceId)
            throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<Op>();
        List<TraceRequestConfig> traceRequests = new ArrayList<>();
        String path = paths.getTraceRequestsPath();
        if (zk.exists(path)) {
            Set<String> trIds = zk.getChildren(path);

            for (String id : trIds) {
                UUID uuid = UUID.fromString(id);
                TraceRequestConfig config = get(uuid);
                if (Objects.equals(config.deviceId, deviceId)) {
                    ops.addAll(prepareDelete(uuid));
                }
            }
        }
        zk.multi(ops);
    }

    public List<Op> prepareUpdate(UUID id, TraceRequestConfig config)
            throws SerializationException {
        return asList(simpleUpdateOp(id, config));
    }
}
