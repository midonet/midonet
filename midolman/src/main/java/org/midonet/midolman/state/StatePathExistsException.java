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
package org.midonet.midolman.state;

import java.util.UUID;

import org.apache.zookeeper.KeeperException.NodeExistsException;

public class StatePathExistsException extends StatePathExceptionBase {
    private static final long serialVersionUID = 1L;

    public StatePathExistsException(String message, String basePath,
                                    NodeExistsException cause) {
        super(message, cause.getPath(), basePath, cause);
    }

    /**
     * Provided for TunnelZoneZkManager(), which generates a
     * StatePathExistsException without an underlying KeeperException.
     */
    public StatePathExistsException(String message, UUID tzId) {
        super(message);
        this.nodeInfo = new NodeInfo(NodeType.TUNNEL_ZONE, tzId);
    }
}
