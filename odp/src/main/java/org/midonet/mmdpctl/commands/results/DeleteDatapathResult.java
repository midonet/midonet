/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.mmdpctl.commands.results;

import java.io.OutputStream;
import java.io.PrintStream;

public class DeleteDatapathResult implements Result {

    @Override
    public void printResult(OutputStream stream) {
        PrintStream out = new PrintStream(stream);
        out.println("Datapath deleted successfully.");
    }

    @Override
    public void printResult() {
        printResult(System.out);
    }
}
