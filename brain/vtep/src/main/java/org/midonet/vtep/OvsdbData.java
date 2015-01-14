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

package org.midonet.vtep;

import org.opendaylight.ovsdb.lib.OvsdbClient;
import org.opendaylight.ovsdb.lib.notation.Condition;
import org.opendaylight.ovsdb.lib.notation.Function;
import org.opendaylight.ovsdb.lib.operations.Operation;
import org.opendaylight.ovsdb.lib.operations.Operations;
import org.opendaylight.ovsdb.lib.operations.Select;
import org.opendaylight.ovsdb.lib.operations.TransactionBuilder;
import org.opendaylight.ovsdb.lib.schema.ColumnSchema;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;

/**
 * Created by ernest on 25/01/15.
 */
public class OvsdbData {
    private final OvsdbClient client;
    private final VtepEndPoint endpoint;
    private DatabaseSchema dbs = null;

    public OvsdbData(OvsdbClient client, VtepEndPoint endpoint) {
        this.client = client;
        this.endpoint = endpoint;
    }

    private void getTunnelIps() {
        TransactionBuilder transaction = client.transactBuilder(dbs);
        GenericTableSchema physSwitchSchema =
            dbs.table("Physical_Switch", GenericTableSchema.class);
        ColumnSchema mgmtIpsColumn = physSwitchSchema.column("management_ips");
        ColumnSchema tunnelIpsColumn = physSwitchSchema.column("tunnel_ips");

        Condition matchedMgmtIp = new Condition(mgmtIpsColumn.getName(),
                                                Function.EQUALS,
                                                endpoint.mgmtIp().toString());

        Select op = Operations.op.select(physSwitchSchema).column(tunnelIpsColumn);
        op.addCondition(matchedMgmtIp);
        Operation generic = op;
        transaction.add(generic);


    }

}
