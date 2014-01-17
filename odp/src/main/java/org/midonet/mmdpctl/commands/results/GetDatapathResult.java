/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.mmdpctl.commands.results;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Set;

import org.midonet.odp.Datapath;
import org.midonet.odp.DpPort;

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
    public void printResult() {
        System.out.println("Datapath name   : " + datapath.getName());
        System.out.println("Datapath index : " + datapath.getIndex());
        Datapath.Stats stats = datapath.getStats();
        System.out.println("Datapath Stats: ");
        System.out.println("  Flows :"+stats.getFlows());
        System.out.println("  Hits  :"+stats.getHits());
        System.out.println("  Lost  :"+stats.getLost());
        System.out.println("  Misses:" +stats.getMisses());
        if (ports != null && (!ports.isEmpty())) {
            for (DpPort port: sortPorts()) {
                System.out.println(assembleString(port));
            }
        } else {
            System.out.println("Datapath does not contain any port.");
        }
    }
}
