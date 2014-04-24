/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.netlink;

import org.midonet.netlink.exceptions.NetlinkException;

/** Callback interface used by netlink connections to notify clients about the
 *  results of their requests. */
public interface Callback<T> {

    public void onSuccess(T data);

    public void onError(NetlinkException e);
}
