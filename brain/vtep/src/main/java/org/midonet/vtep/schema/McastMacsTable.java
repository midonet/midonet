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

package org.midonet.vtep.schema;

import java.util.List;

import org.opendaylight.ovsdb.lib.notation.Row;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.lib.schema.ColumnSchema;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;

import org.midonet.cluster.data.vtep.model.MacEntry;
import org.midonet.cluster.data.vtep.model.McastMac;

import static org.midonet.vtep.OvsdbUtil.fromOvsdbUUID;

/**
 * Specific schema section for the multicast mac tables
 */
public abstract class McastMacsTable extends MacsTable {
    static private final String COL_LOCATOR_SET = "locator_set";

    protected McastMacsTable(DatabaseSchema databaseSchema, String tableName) {
        super(databaseSchema, tableName);
    }

    public Boolean isUcast() {
        return false;
    }

    /** Get the schema of the columns of this table */
    public List<ColumnSchema<GenericTableSchema, ?>> getColumnSchemas() {
        List<ColumnSchema<GenericTableSchema, ?>> cols = super.getColumnSchemas();
        cols.add(getLocatorSetSchema());
        return cols;
    }

    /** Get the schema for the locator column */
    public ColumnSchema<GenericTableSchema, UUID> getLocatorSetSchema() {
        return tableSchema.column(COL_LOCATOR_SET, UUID.class);
    }

    /** Map the schema for the location column */
    public ColumnSchema<GenericTableSchema, UUID> getLocationIdSchema() {
        return getLocatorSetSchema();
    }

    /**
     * Extract the locator corresponding to the vxlan tunnel ip,
     * returning null if not set
     */
    public java.util.UUID parseLocatorSet(Row<GenericTableSchema> row) {
        return (row == null)? null:
               fromOvsdbUUID(row.getColumn(getLocatorSetSchema()).getData());
    }

    /**
     * Extract the entry information
     */
    public McastMac parseMcastMac(Row<GenericTableSchema> row) {
        return new McastMac(parseUuid(row), parseLogicalSwitch(row),
                            parseMac(row), parseIpaddr(row),
                            parseLocatorSet(row));
    }

    public MacEntry parseMacEntry(Row<GenericTableSchema> row) {
        return parseMcastMac(row);
    }
}
