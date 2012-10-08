package com.midokura.mmdpctl.commands.results;

public class DeleteDatapathResult implements Result {

    @Override
    public void printResult() {
        System.out.println("Datapath deleted successfully.");
    }
}
