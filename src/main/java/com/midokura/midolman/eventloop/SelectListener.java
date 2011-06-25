package com.midokura.midolman.eventloop;

import java.io.IOException;
import java.nio.channels.SelectionKey;

/**
 * 
 * Originally from org.openflowj.examples
 * 
 */
public interface SelectListener {

    void handleEvent(SelectionKey key) throws IOException;

}
