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
import java.util.Map;

import org.opendaylight.ovsdb.lib.notation.Condition;
import org.opendaylight.ovsdb.lib.notation.Function;
import org.opendaylight.ovsdb.lib.notation.Row;
import org.opendaylight.ovsdb.lib.operations.Insert;
import org.opendaylight.ovsdb.lib.schema.ColumnSchema;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;

import org.midonet.cluster.data.vtep.model.VtepEntry;
import org.midonet.packets.IPv4Addr;
import org.midonet.cluster.data.vtep.model.PhysicalLocator;

/**
 * Schema for the Ovsdb physical locator table
 */
public final class PhysicalLocatorTable extends Table {
    static public final String TB_NAME = "Physical_Locator";
    static private final String COL_ENCAPSULATION = "encapsulation_type";
    static private final String COL_DST_IP = "dst_ip";
    static private final String COL_BFD = "bfd";
    static private final String COL_BFD_STATUS = "bfd_status";

    public PhysicalLocatorTable(DatabaseSchema databaseSchema) {
        super(databaseSchema, TB_NAME, PhysicalLocator.class);
    }

    public String getName() {
        return TB_NAME;
    }

    /** Get the schema of the columns of this table */
    @Override
    public List<ColumnSchema<GenericTableSchema, ?>> getColumnSchemas() {
        List<ColumnSchema<GenericTableSchema, ?>> cols =
            super.partialColumnSchemas();
        cols.add(getEncapsulationSchema());
        cols.add(getDstIpSchema());
        cols.add(getBfdSchema());
        cols.add(getBfdStatusSchema());
        return cols;
    }

    /** Get the schema for the encapsulation type */
    private ColumnSchema<GenericTableSchema, String> getEncapsulationSchema() {
        return tableSchema.column(COL_ENCAPSULATION, String.class);
    }

    /** Get the schema for the tunnel destination ip */
    private ColumnSchema<GenericTableSchema, String> getDstIpSchema() {
        return tableSchema.column(COL_DST_IP, String.class);
    }

    /** Get the schema for BFD */
    private ColumnSchema<GenericTableSchema, Map> getBfdSchema() {
        return tableSchema.column(COL_BFD, Map.class);
    }

    /** Get the schema for BFD status */
    private ColumnSchema<GenericTableSchema, Map> getBfdStatusSchema() {
        return tableSchema.column(COL_BFD_STATUS, Map.class);
    }

    /** Generate a matcher condition for the destination ip */
    static public Condition getDstIpMatcher(IPv4Addr value) {
        return new Condition(COL_DST_IP, Function.EQUALS, value.toString());
    }

    /**
     * Extract the encapsulation type, returning null if not set or empty
     */
    private String parseEncapsulation(Row<GenericTableSchema> row) {
        return extractString(row, getEncapsulationSchema());
    }

    /**
     * Extract the destination ip address
     */
    private IPv4Addr parseDstIp(Row<GenericTableSchema> row) {
        String value = extractString(row, getDstIpSchema());
        return (value == null)? null : IPv4Addr.fromString(value);
    }

    /**
     * Extract the physical locator information from the table entry
     */
    @Override
    @SuppressWarnings(value = "unckecked")
    public <E extends VtepEntry>
    E parseEntry(Row<GenericTableSchema> row, Class<E> clazz)
        throws IllegalArgumentException {
        ensureOutputClass(clazz);
        return (row == null)? null:
               (E)PhysicalLocator.apply(parseUuid(row), parseDstIp(row),
                                        parseEncapsulation(row));
    }

    /**
     * Generate an insert command
     */
    @Override
    public <E extends VtepEntry> Table.OvsdbInsert insert(E row)
        throws IllegalArgumentException {
        Insert<GenericTableSchema> op = newInsert(row);
        PhysicalLocator loc = (PhysicalLocator)row;
        op.value(getEncapsulationSchema(), loc.encapsulation());
        op.value(getDstIpSchema(), loc.dstIpString());
        return new OvsdbInsert(op);
    }

    @Override
    public <E extends VtepEntry> Row<GenericTableSchema> generateRow(
        E entry) throws IllegalArgumentException {
        Row<GenericTableSchema> row = super.generateRow(entry);
        PhysicalLocator data = (PhysicalLocator)entry;
        addToRow(row, getEncapsulationSchema(), data.encapsulation());
        addToRow(row, getDstIpSchema(), data.dstIpString());
        return row;
    }
}
