/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.netlink;

import com.midokura.netlink.exceptions.NetlinkException;

/**
 * Callback interface which forces the exception type to {@link NetlinkException}.
 * <p/>
 * Used by the netlink library.
 *
 * @see com.midokura.netlink.protos.OvsDatapathConnection
 */
public interface Callback<T>
    extends com.midokura.util.functors.Callback<T, NetlinkException> {

}
