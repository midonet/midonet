/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.mmdpctl.commands.results;

import java.io.OutputStream;

/**
 * As this is the result for a Command Line tool, all the command results need to be displayed correctly on the screen.
 * This interface provides the method that the tool will use to display the results.
 */
public interface Result {

    /**
     * Outputs the result to the screen in a nice formatted way.
     */
    void printResult();

    void printResult(OutputStream out);
}
