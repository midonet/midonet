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
import java.util.Set;

import org.opendaylight.ovsdb.lib.notation.Condition;
import org.opendaylight.ovsdb.lib.notation.Function;
import org.opendaylight.ovsdb.lib.notation.Row;
import org.opendaylight.ovsdb.lib.operations.Insert;
import org.opendaylight.ovsdb.lib.schema.ColumnSchema;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;

import org.midonet.cluster.data.vtep.model.PhysicalLocatorSet;

import static org.midonet.southbound.vtep.OvsdbUtil.toOvsdb;
import static org.midonet.southbound.vtep.OvsdbUtil.toOvsdbUuid;

/**
 * Schema for the Ovsdb physical switch table
 */
public final class PhysicalLocatorSetTable extends Table<PhysicalLocatorSet> {
    static public final String TB_NAME = "Physical_Locator_Set";
    static private final String COL_LOCATORS = "locators";

    public PhysicalLocatorSetTable(DatabaseSchema databaseSchema) {
        super(databaseSchema, TB_NAME, PhysicalLocatorSet.class);
    }

    public String getName() {
        return TB_NAME;
    }

    /** Get the schema of the columns of this table */
    @Override
    public List<ColumnSchema<GenericTableSchema, ?>> getColumnSchemas() {
        List<ColumnSchema<GenericTableSchema, ?>> cols =
            super.partialColumnSchemas();
        cols.add(getLocatorsSchema());
        return cols;
    }

    /** Get the schema for the set of the physical locators */
    private ColumnSchema<GenericTableSchema, Set> getLocatorsSchema() {
        return tableSchema.column(COL_LOCATORS, Set.class);
    }

    /** Generate a matcher condition for a locator UUID */
    static public Condition getLocatorMatcher(java.util.UUID locId) {
        return new Condition(COL_LOCATORS, Function.INCLUDES, toOvsdb(locId));
    }

    /**
     * Extract the set of locator ids
     */
    @SuppressWarnings("unchecked")
    private Set<String> parseLocators(Row<GenericTableSchema> row) {
        return extractSet(row, getLocatorsSchema());
    }

    /**
     * Extract the locator set object
     */
    @Override
    public PhysicalLocatorSet parseEntry(Row<GenericTableSchema> row)
        throws IllegalArgumentException {
        ensureOutputClass(PhysicalLocatorSet.class);
        return (row == null)? null:
               PhysicalLocatorSet.apply(parseUuid(row), parseLocators(row));
    }

    /**
     * Generate an insert command
     */
    @Override
    public Table.OvsdbInsert insert(PhysicalLocatorSet entry, String id)
        throws IllegalArgumentException {
        Insert<GenericTableSchema> op = newInsert(entry, id);
        op.value(getLocatorsSchema(), toOvsdbUuid(entry.locatorIds()));
        return new OvsdbInsert(op);
    }

    @Override
    public Table.OvsdbDelete delete(PhysicalLocatorSet entry) {
        return deleteById(entry.uuid());
    }

    @Override
    public  Row<GenericTableSchema> generateRow(PhysicalLocatorSet entry)
        throws IllegalArgumentException {
        Row<GenericTableSchema> row = super.generateRow(entry);
        addToRow(row, getLocatorsSchema(), toOvsdbUuid(entry.locatorIds()));
        return row;
    }
}
