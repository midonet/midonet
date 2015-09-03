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

package org.midonet.southbound.vtep;

import org.opendaylight.ovsdb.lib.OvsdbClient;
import org.opendaylight.ovsdb.lib.OvsdbConnectionInfo;
import org.opendaylight.ovsdb.lib.operations.OperationResult;

import org.midonet.cluster.data.vtep.model.VtepEndPoint;
import org.midonet.packets.IPv4Addr;


/**
 * Common utility Procedures related to ovsdb-based vtep management
 */
public class OvsdbTools {

    /**
     * Extract vtep end point information (management ip address and port)
     * from an OvsdbClient
     */
    static public VtepEndPoint endPointFromOvsdbClient(OvsdbClient client) {
        OvsdbConnectionInfo connInfo = client.getConnectionInfo();
        return VtepEndPoint.apply(
            IPv4Addr.fromBytes(connInfo.getRemoteAddress().getAddress()),
            connInfo.getRemotePort());
    }

    static public Boolean opResultHasError(OperationResult r) {
        return r.getError() != null && !r.getError().trim().isEmpty();
    }

}
