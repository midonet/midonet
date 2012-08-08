/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.datapath

import com.midokura.netlink.exceptions.NetlinkException
import com.midokura.netlink.{Callback => NetlinkCallback}

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
