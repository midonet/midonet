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

package org.midonet.southbound.vtep.schema;

import java.util.List;

import org.opendaylight.ovsdb.lib.notation.Row;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.lib.schema.ColumnSchema;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;

import org.midonet.cluster.data.vtep.model.McastMac;

/**
 * Specific schema section for the multicast mac tables
 */
public abstract class McastMacsTable extends MacsTable<McastMac> {
    static private final String COL_LOCATOR_SET = "locator_set";

    protected McastMacsTable(DatabaseSchema databaseSchema, String tableName) {
        super(databaseSchema, tableName, McastMac.class);
    }

    /** Get the schema of the columns of this table */
    @Override
    public List<ColumnSchema<GenericTableSchema, ?>> getColumnSchemas() {
        List<ColumnSchema<GenericTableSchema, ?>> cols =
            super.partialColumnSchemas();
        cols.add(getLocatorSetSchema());
        return cols;
    }

    /** Get the schema for the locator column */
    protected ColumnSchema<GenericTableSchema, UUID> getLocatorSetSchema() {
        return tableSchema.column(COL_LOCATOR_SET, UUID.class);
    }

    /** Map the schema for the location column */
    @Override
    protected ColumnSchema<GenericTableSchema, UUID> getLocationIdSchema() {
        return getLocatorSetSchema();
    }

    /**
     * Extract the locator corresponding to the vxlan tunnel ip,
     * returning null if not set
     */
    protected java.util.UUID parseLocatorSet(Row<GenericTableSchema> row) {
        return extractUuid(row, getLocatorSetSchema());
    }

    /**
     * Extract the entry information
     */
    @Override
    public McastMac parseEntry(Row<GenericTableSchema> row)
        throws IllegalArgumentException {
        ensureOutputClass(McastMac.class);
        return (row == null)? null:
               McastMac.apply(parseUuid(row), parseLogicalSwitch(row),
                                 parseMac(row), parseIpaddr(row),
                                 parseLocatorSet(row).toString());
    }
}
