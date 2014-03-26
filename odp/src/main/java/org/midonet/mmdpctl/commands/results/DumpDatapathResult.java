/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.mmdpctl.commands.results;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.*;

import org.midonet.odp.Datapath;
import org.midonet.odp.Flow;

public class DumpDatapathResult implements Result {

    static public interface Filter {
        boolean predicate(Flow flow);
    }

    Set<Flow> flows;
    Datapath datapath;

    public DumpDatapathResult(Datapath datapath, Set<Flow> flows) {
        this.flows = flows;
        this.datapath = datapath;
    }

    public ArrayList<Flow> sortFlows(Set<Flow> flows) {
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
    public void printResult(OutputStream stream) {
        PrintStream out = new PrintStream(stream);
        out.println("" + flows.size() + " flows");
        for (Flow flow : sortFlows(flows)) {
            out.println("  Flow:");
            for (String s: flow.toPrettyStrings()) {
                out.println("    " + s);
            }
        }
    }

    @Override
    public void printResult() {
        printResult(System.out);
    }
}
