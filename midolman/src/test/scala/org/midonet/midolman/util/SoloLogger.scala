/******************************************************************************
 *                                                                            *
 *      Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.         *
 *                                                                            *
 ******************************************************************************/

package org.midonet.midolman

import akka.event.LoggingAdapter
import org.slf4j.LoggerFactory

/** crude logging facility for actor related testing */
class SoloLogger(client: Class[_]) extends LoggingAdapter {

    val log = LoggerFactory.getLogger(client)

    var isErrorEnabled = true
    var isWarningEnabled = true
    var isInfoEnabled = true
    var isDebugEnabled = true

    protected def notifyError(cause: Throwable, msg: String) {
        log.error(msg, cause)
    }
    protected def notifyError(msg: String) { log.error(msg) }
    protected def notifyWarning(msg: String) { log.warn(msg) }
    protected def notifyInfo(msg: String) { log.info(msg) }
    protected def notifyDebug(msg: String) { log.debug(msg) }

}

object SoloLogger { def apply[T](client: Class[T]) = new SoloLogger(client) }
