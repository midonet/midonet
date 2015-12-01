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
import java.util.Map;

import org.opendaylight.ovsdb.lib.notation.Row;
import org.opendaylight.ovsdb.lib.operations.Insert;
import org.opendaylight.ovsdb.lib.schema.ColumnSchema;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;

import org.midonet.cluster.data.vtep.model.PhysicalLocator;
import org.midonet.packets.IPv4Addr;

/**
 * Schema for the Ovsdb physical locator table
 */
public final class PhysicalLocatorTable extends Table<PhysicalLocator> {
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
        ColumnSchema bfdSchema = getBfdSchema();
        if (bfdSchema != null) {
            cols.add(bfdSchema);
        }
        ColumnSchema bfdStatusSchema = getBfdStatusSchema();
        if (bfdStatusSchema != null) {
            cols.add(bfdStatusSchema);
        }
        return cols;
    }

    @Override
    public List<ColumnSchema<GenericTableSchema, ?>> getMandatoryColumnSchemas() {
        List<ColumnSchema<GenericTableSchema, ?>> cols =
            super.partialColumnSchemas();
        cols.add(getEncapsulationSchema());
        cols.add(getDstIpSchema());
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
    @SuppressWarnings(value = "unchecked")
    public PhysicalLocator parseEntry(Row<GenericTableSchema> row)
        throws IllegalArgumentException {
        ensureOutputClass(PhysicalLocator.class);
        return (row == null)? null:
               PhysicalLocator.apply(parseUuid(row), parseDstIp(row));
    }

    /**
     * Generate an insert command
     */
    @Override
    public Table.OvsdbInsert insert(PhysicalLocator entry, String id)
        throws IllegalArgumentException {
        Insert<GenericTableSchema> op = newInsert(entry, id);
        op.value(getEncapsulationSchema(), entry.encapsulation());
        op.value(getDstIpSchema(), entry.dstIpString());
        return new OvsdbInsert(op);
    }

    @Override
    public Table.OvsdbDelete delete(PhysicalLocator entry) {
        return deleteById(entry.uuid());
    }

    @Override
    public Row<GenericTableSchema> generateRow(PhysicalLocator entry)
        throws IllegalArgumentException {
        Row<GenericTableSchema> row = super.generateRow(entry);
        addToRow(row, getEncapsulationSchema(), entry.encapsulation());
        addToRow(row, getDstIpSchema(), entry.dstIpString());
        return row;
    }
}
