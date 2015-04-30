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
import org.midonet.cluster.data.vtep.model.UcastMac;

/**
 * Specific schema section for the unicast mac tables
 */
public abstract class UcastMacsTable extends MacsTable {
    static private final String COL_LOCATOR = "locator";

    protected UcastMacsTable(DatabaseSchema databaseSchema, String tableName) {
        super(databaseSchema, tableName);
    }

    @Override
    public Boolean isUcastTable() {
        return true;
    }

    /** Get the schema of the columns of this table */
    public List<ColumnSchema<GenericTableSchema, ?>> getColumnSchemas() {
        List<ColumnSchema<GenericTableSchema, ?>> cols = super.getColumnSchemas();
        cols.add(getLocatorSchema());
        return cols;
    }

    /** Get the schema for the locator column */
    protected ColumnSchema<GenericTableSchema, UUID> getLocatorSchema() {
        return tableSchema.column(COL_LOCATOR, UUID.class);
    }

    /** Map the schema for the location column */
    protected ColumnSchema<GenericTableSchema, UUID> getLocationIdSchema() {
        return getLocatorSchema();
    }


    /**
     * Extract the locator corresponding to the vxlan tunnel ip,
     * returning null if not set
     */
    protected java.util.UUID parseLocator(Row<GenericTableSchema> row) {
        return extractUuid(row, getLocationIdSchema());
    }

    /**
     * Extract the entry information
     */
    public UcastMac parseUcastMac(Row<GenericTableSchema> row) {
       return new UcastMac(parseUuid(row), parseLogicalSwitch(row),
                           parseMac(row), parseIpaddr(row),
                           parseLocator(row));
    }

    public MacEntry parseMacEntry(Row<GenericTableSchema> row) {
        return parseUcastMac(row);
    }
}
