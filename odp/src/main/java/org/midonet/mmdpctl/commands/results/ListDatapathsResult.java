/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.mmdpctl.commands.results;

import org.midonet.odp.Datapath;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Set;

public class ListDatapathsResult implements Result {
    Set<Datapath> datapaths;

    public ListDatapathsResult(Set<Datapath> datapaths) {
        this.datapaths = datapaths;
    }

    @Override
    public void printResult(OutputStream stream) {
        PrintStream out = new PrintStream(stream);
        if (datapaths.size() > 0) {
        out.println("Found " + datapaths.size() + " datapaths:");
        for (Datapath datapath : datapaths) {
            out.println("\t"+datapath.getName());
        }
        } else {
            out.println("Could not find any installed datapath.");
        }
    }

    @Override
    public void printResult() {
        printResult(System.out);
    }

}
