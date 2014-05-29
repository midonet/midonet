/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.mmdpctl.commands.results;

import org.midonet.odp.Datapath;
import org.midonet.odp.DpPort;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Set;

public class GetDatapathResult implements Result {

    Datapath datapath;
    Set<DpPort> ports;

    public GetDatapathResult(Datapath datapath, Set<DpPort> ports) {
        this.datapath = datapath;
        this.ports = ports;
    }

    public String assembleString(DpPort p) {
        return "Port #" + p.getPortNo() + " \"" + p.getName() + "\"  "
            + p.getType().toString() +  "  " + p.getStats().toString();
    }

    public ArrayList<DpPort> sortPorts() {
        ArrayList<DpPort> toPrint = new ArrayList<>(ports);

        Collections.sort(toPrint, new Comparator<DpPort>() {
            @Override public int compare(DpPort o1, DpPort o2) {
                return o1.getPortNo().compareTo(o2.getPortNo());
            }
        });

        return toPrint;
    }

    @Override
    public void printResult(OutputStream stream) {
        PrintStream out = new PrintStream(stream);
        out.println("Datapath name   : " + datapath.getName());
        out.println("Datapath index : " + datapath.getIndex());
        Datapath.Stats stats = datapath.getStats();
        out.println("Datapath Stats: ");
        out.println("  Flows :"+stats.getFlows());
        out.println("  Hits  :"+stats.getHits());
        out.println("  Lost  :"+stats.getLost());
        out.println("  Misses:" +stats.getMisses());
        if (ports != null && (!ports.isEmpty())) {
            for (DpPort port: sortPorts()) {
                out.println(assembleString(port));
            }
        } else {
            out.println("Datapath does not contain any port.");
        }
    }

    @Override
    public void printResult() {
        printResult(System.out);
    }

}
