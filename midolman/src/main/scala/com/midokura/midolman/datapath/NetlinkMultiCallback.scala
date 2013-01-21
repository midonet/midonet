/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.datapath

import com.midokura.util.functors.Callback.{Result, MultiResult}
import com.midokura.netlink.{Callback => NetlinkCallback}
import com.midokura.netlink.exceptions.NetlinkException
import scala.collection.JavaConversions._

/**
 * A specialization of the `NetlinkCallback` which can act as a multi callback results collector.
 *
 * @tparam T is the underlying data type
 *
 */
class NetlinkMultiCallback[T] extends NetlinkCallback[MultiResult[T]] {

    def onSuccess(multi: MultiResult[T]) {
        if (multi.hasTimeouts || multi.hasExceptions) {
            for (result <- multi) {
                if (result.timeout())
                    onTimeout(result)
                else if (result.exception() != null)
                    onError(result)
            }
        }
    }

    final def onTimeout() {}

    def onTimeout(result: Result[T]) {
//        log.error("Operation \"{}\" timed out.", result.operation)
    }

    final def onError(e: NetlinkException) {}

    def onError(result: Result[T]) {
//        log.error(result.exception(), "Operation \"{}\" failed.", result.operation)
    }
}
