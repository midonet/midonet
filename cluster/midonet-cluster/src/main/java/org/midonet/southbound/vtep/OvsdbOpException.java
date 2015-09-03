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

import java.util.List;

import org.opendaylight.ovsdb.lib.OvsdbClient;
import org.opendaylight.ovsdb.lib.operations.Operation;
import org.opendaylight.ovsdb.lib.operations.OperationResult;

import org.midonet.southbound.vtep.schema.Table;

public class OvsdbOpException extends OvsdbException {

    public final Integer opIndex;
    public final Operation op;
    public final OperationResult result;

    public OvsdbOpException(OvsdbClient client,
                            List<Table.OvsdbOperation> ops,
                            Integer idx, OperationResult r) {
        super(client, (idx >= ops.size())?
                      String.format("%s: %s", r.getError(), r.getDetails()):
                      String.format("%s on %s: %s: %s", ops.get(idx).op.getOp(),
                                    ops.get(idx).op.getTable(), r.getError(),
                                    r.getDetails())
        );
        this.opIndex = idx;
        this.op = (idx >= ops.size())? null: ops.get(idx).op;
        this.result = r;
    }
}
