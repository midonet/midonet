/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.mmdpctl.commands.results;

public class DeleteDatapathResult implements Result {

    @Override
    public void printResult() {
        System.out.println("Datapath deleted successfully.");
    }
}
