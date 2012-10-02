package com.midokura.mmdpctl.results;

import com.midokura.sdn.dp.Flow;

import java.util.Set;


public class DumpDatapathResult implements Result {
    Set<Flow> flows;

    public DumpDatapathResult(Set<Flow> flows) {
        this.flows = flows;
    }

    @Override
    public void printResult() {
        if (flows.isEmpty()) {
            System.out.println("No flows for the selected datapath.");
        } else {
            System.out.println("Displaying " + flows.size() + " flows: ");
            for (Flow flow : flows) {
                System.out.println("\t"+flow.toString());
            }
        }
    }
}
