/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test.openflow;

import java.util.List;

import junit.framework.Assert;

import org.openflow.protocol.statistics.OFTableStatistics;

public class TableStats {

    byte tableId;
    OpenFlowStats controller;
    OFTableStatistics stat;

    public TableStats(byte tableId, OpenFlowStats controller,
            OFTableStatistics stat) {
        this.tableId = tableId;
        this.controller = controller;
        this.stat = stat;
    }

    public byte getTableId() {
        return tableId;
    }

    /**
     * Return the TableStats from the list whose match field is equal to the one
     * in 'this'. Assert. This is a convenience method that can be used like
     * this:
     * 
     * <pre>
     * {
     *     List&lt;TableStats&gt; stats = controller.getTableStats();
     *     TableStats tStat = stats.get(0);
     *     tStat.expectActive(4).expectLookups(20).expectMatches(10);
     *     stats = controller.getTableStats(); // refresh stats from switch
     *     tStat.expectActive(5).expectLookups(25).expectMatches(13);
     * }
     * </pre>
     * 
     * @return The equivalent FlowStat from the list or 'this' if none is found
     *         in the list. Assert.fail with a message if no equivalent is found
     *         in the list.
     */
    public TableStats findSameInList(List<TableStats> stats) {
        for (TableStats tStat : stats) {
            if (tableId == tStat.tableId)
                return tStat;
        }
        Assert.fail("Did not find a TableStats with the same tableId.");
        return this;
    }

    public TableStats expectActive(int i) {
        Assert.assertEquals(i, stat.getActiveCount());
        return this;
    }

    public TableStats expectLookups(int i) {
        Assert.assertEquals(i, stat.getLookupCount());
        return this;
    }

    public TableStats expectMatches(int i) {
        Assert.assertEquals(i, stat.getMatchedCount());
        return this;
    }

    @Override
    public String toString() {
        return "TableStats{" +
            "tableId=" + tableId +
            '}';
    }
}
