/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.mmdpctl.commands.results;

import org.midonet.odp.Datapath;
import org.midonet.odp.Port;

import java.util.Set;

public class GetDatapathResult implements Result {

    Datapath datapath;
    Set<Port<?,?>> ports;

    public GetDatapathResult(Datapath datapath, Set<Port<?,?>> ports) {
        this.datapath = datapath;
        this.ports = ports;
    }

    @Override
    public void printResult() {
        System.out.println("Datpath name   : " + datapath.getName());
        System.out.println("Datapath index : " + datapath.getIndex());
        Datapath.Stats stats = datapath.getStats();
        System.out.println("Datapath Stats: ");
        System.out.println("  Flows :"+stats.getFlows());
        System.out.println("  Hits  :"+stats.getHits());
        System.out.println("  Lost  :"+stats.getLost());
        System.out.println("  Misses:" +stats.getMisses());
        if (ports != null && (!ports.isEmpty())) {
            for (Port<?,?> port: ports) {
                System.out.println("Port Name: " + port.getName());
                System.out.println("  Port number: " + port.getPortNo());
                System.out.println("  Port type  : " + port.getType().toString());
                System.out.println("  "+port.getStats().toString());
            }
        } else {
            System.out.println("Datapath does not contain any port.");
        }
    }
}
