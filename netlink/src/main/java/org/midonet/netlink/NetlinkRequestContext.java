/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.netlink;

public interface NetlinkRequestContext {
    short commandFamily();
    byte command();
    byte version();
}
