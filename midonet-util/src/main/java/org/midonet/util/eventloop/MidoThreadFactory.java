/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.util.eventloop;

import java.util.concurrent.ThreadFactory;

public class MidoThreadFactory implements ThreadFactory {

    private int counter = 0;
    private String prefix = "";

    public MidoThreadFactory(String prefix) {
        this.prefix = prefix;
    }

    public Thread newThread(Runnable r) {
        return new Thread(r, prefix + "-" + counter++);
    }
}
