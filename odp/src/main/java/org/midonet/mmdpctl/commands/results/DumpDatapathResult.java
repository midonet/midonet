/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.mmdpctl.commands.results;

import java.util.Set;
import java.util.List;

import org.midonet.odp.Datapath;
import org.midonet.odp.Flow;

public class DumpDatapathResult implements Result {
    Set<Flow> flows;
    Datapath datapath;

    public DumpDatapathResult(Datapath datapath, Set<Flow> flows) {
        this.flows = flows;
        this.datapath = datapath;
    }

    @Override
    public void printResult() {
        System.out.println("" + flows.size() + " flows");
        for (Flow flow : flows) {
            System.out.println("  Flow:");
            for (String s: flow.toPrettyStrings()) {
                System.out.println("    " + s);
            }
        }
    }
}
