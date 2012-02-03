/*
 * @(#)ChainTable.java        1.6 11/12/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.core;

import java.util.HashMap;
import java.util.Map;

import com.midokura.midolman.mgmt.data.dto.client.DtoRuleChain;

/**
 * Enum for chain table
 *
 * @version 1.6 15 Dec 2011
 * @author Ryu Ishimoto
 */
public enum ChainTable {

    /**
     * NAT table.
     */
    NAT("nat");

    private final String value;

    private static final String PRE_ROUTING = "pre_routing";
    private static final String POST_ROUTING = "post_routing";

    private ChainTable(String val) {
        this.value = val;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Enum#toString()
     */
    @Override
    public String toString() {
        return value;
    }

    /**
     * Built-in chain names.
     */
    public final static Map<ChainTable, String[]> builtInChains;
    static {
        builtInChains = new HashMap<ChainTable, String[]>();
        builtInChains.put(NAT, new String[] { PRE_ROUTING, POST_ROUTING });
    }

    /**
     * Checks whether the given table is built-in.
     *
     * @param table
     *            Name to check
     * @return True if built-in
     */
    public static boolean isBuiltInTableName(String table) {

        if (table == null) {
            throw new IllegalArgumentException("table cannot be null");
        }

        ChainTable[] tables = ChainTable.values();
        for (ChainTable chainTable : tables) {
            if (chainTable.toString().equals(table.toLowerCase())) {
                return true;
            }
        }
        return false;

    }

    /**
     * Get an array of built-in names
     *
     * @param table
     *            Table to get the names for.
     * @return An array of names of the built-in chains for a table.
     */
    public static String[] getBuiltInChainNames(ChainTable table) {
        return builtInChains.get(table);
    }

    /**
     * Checks whether the given name is a built-in chain name.
     *
     * @param table
     *            Table to check.
     * @param name
     *            Name to check.
     * @return True if built-in
     */
    public static boolean isBuiltInChainName(ChainTable table, String name) {

        if (name == null) {
            throw new IllegalArgumentException("name cannot be null");
        }

        String[] names = getBuiltInChainNames(table);
        for (String chainName : names) {
            if (chainName.toLowerCase().equals(name.toLowerCase())) {
                return true;
            }
        }
        return false;

    }

    public static boolean isBuiltInChainName(DtoRuleChain.ChainTable table,
                                             String name) {
        return isBuiltInChainName(Enum.valueOf(ChainTable.class, table.name()),
                name);
    }

}
