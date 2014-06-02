/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.event.api;

import org.midonet.event.AbstractEvent;

public class NsdbEvent extends AbstractEvent {

    private static final String eventKey = "org.midonet.event.api.Nsdb";

    public NsdbEvent() {
        super(eventKey);
    }

    public void connect() {
        handleEvent("CONNECT");
    }

    public void disconnect() {
        handleEvent("DISCONNECT");
    }

    public void connExpire() {
        handleEvent("CONN_EXPIRE");
    }
}
