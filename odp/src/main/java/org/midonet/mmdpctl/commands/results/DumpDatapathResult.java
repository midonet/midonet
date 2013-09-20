/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.mmdpctl.commands.results;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Set;

import org.midonet.odp.Datapath;
import org.midonet.odp.Flow;

public class DumpDatapathResult implements Result {
    Set<Flow> flows;
    Datapath datapath;

    public DumpDatapathResult(Datapath datapath, Set<Flow> flows) {
        this.flows = flows;
        this.datapath = datapath;
    }

    public ArrayList<Flow> sortFlows() {
        ArrayList<Flow> toPrint = new ArrayList<>(flows);

        Collections.sort(toPrint, new Comparator<Flow>() {
            @Override public int compare(Flow o1, Flow o2) {
                long t1 = 0, t2 = 0;
                if (o1.getLastUsedTime() != null) t1 = o1.getLastUsedTime();
                if (o2.getLastUsedTime() != null) t2 = o2.getLastUsedTime();
                return Long.compare(t1, t2); // oldest first (lowest long)
            }
        });

        return toPrint;
    }

    @Override
    public void printResult() {
        System.out.println("" + flows.size() + " flows");
        for (Flow flow : sortFlows()) {
            System.out.println("  Flow:");
            for (String s: flow.toPrettyStrings()) {
                System.out.println("    " + s);
            }
        }
    }
}
