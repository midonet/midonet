package org.midonet.vtep.schema;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.opendaylight.ovsdb.lib.notation.Condition;
import org.opendaylight.ovsdb.lib.notation.Function;
import org.opendaylight.ovsdb.lib.notation.Row;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.lib.operations.Insert;
import org.opendaylight.ovsdb.lib.schema.ColumnSchema;
import org.opendaylight.ovsdb.lib.schema.DatabaseSchema;
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema;


/**
 * Schema for the Ovsdb physical switch table
 */
public final class PhysicalLocatorSetTable extends Table {
    static private final String TB_NAME = "Physical_Locator_Set";
    static private final String COL_LOCATORS = "locators";

    public PhysicalLocatorSetTable(DatabaseSchema databaseSchema) {
        super(databaseSchema, TB_NAME);
    }

    /** Get the schema of the columns of this table */
    public List<ColumnSchema<GenericTableSchema, ?>> getColumnSchemas() {
        List<ColumnSchema<GenericTableSchema, ?>> cols = super.getColumnSchemas();
        cols.add(getLocatorsSchema());
        return cols;
    }

    /** Get the schema for the name of the physical switch (id) */
    public ColumnSchema<GenericTableSchema, Set> getLocatorsSchema() {
        return tableSchema.column(COL_LOCATORS, Set.class);
    }

    /** Generate a matcher condition for an UUID */
    public Condition getLocatorMatcher(UUID locId) {
        return new Condition(COL_LOCATORS, Function.INCLUDES, locId);
    }

    /**
     * Extract the set of management ips
     */
    public Set<UUID> parseLocators(Row<GenericTableSchema> row) {
        return (row == null)? null:
            (Set<UUID>)row.getColumn(getLocatorsSchema()).getData();
    }

    /**
     * Generate an insert command
     */
    public Insert<GenericTableSchema> insert(UUID locId) {
        Set<UUID> locatorSet = new HashSet<>();
        locatorSet.add(locId);
        Insert<GenericTableSchema> op = super.insert();
        op.value(getLocatorsSchema(), locatorSet);
        return op;
    }
}
