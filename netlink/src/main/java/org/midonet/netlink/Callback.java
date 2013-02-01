/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.netlink;

import org.midonet.netlink.exceptions.NetlinkException;

/**
 * Callback interface which forces the exception type to {@link NetlinkException}.
 * <p/>
 * Used by the netlink library.
 *
 * @see org.midonet.sdn.dp.protos.OvsDatapathConnection
 */
public interface Callback<T>
    extends org.midonet.util.functors.Callback<T, NetlinkException> {

}
