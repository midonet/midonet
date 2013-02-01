/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.datapath

import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.netlink.{Callback => NetlinkCallback}

/**
 * This is a `NetlinkCallback` specialization which collapses the `onTimeout()`
 * and `onError()` into a single method `handleError()`.
 *
 * @tparam T is the type of the data handled by this callback.
 */
abstract class ErrorHandlingCallback[T] extends NetlinkCallback[T] {

    def onTimeout() {
        handleError(null, timeout = true)
    }

    def onError(e: NetlinkException) {
        handleError(e, timeout = false)
    }

    def handleError(ex: NetlinkException, timeout: Boolean)
}
