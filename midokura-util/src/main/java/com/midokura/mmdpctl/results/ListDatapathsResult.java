package com.midokura.mmdpctl.results;

import com.midokura.sdn.dp.Datapath;

import java.util.Set;

/**
 * Created with IntelliJ IDEA.
 * User: marc
 * Date: 01/10/2012
 * Time: 13:41
 * To change this template use File | Settings | File Templates.
 */
public class ListDatapathsResult implements Result {
    Set<Datapath> datapaths;

    public ListDatapathsResult(Set<Datapath> datapaths) {
        this.datapaths = datapaths;
    }

    @Override
    public void printResult() {
        if (datapaths.size() > 0) {
        System.out.println("Found " + datapaths.size() + " datapaths:");
        for (Datapath datapath : datapaths) {
            System.out.println("\t"+datapath.getName());
        }
        } else {
            System.out.println("Could not find any installed datapath.");
        }
    }
}
