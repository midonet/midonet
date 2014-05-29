/******************************************************************************
 *                                                                            *
 *      Copyright (c) 2013 Midokura SARL, All Rights Reserved.         *
 *                                                                            *
 ******************************************************************************/

package org.midonet.midolman.state

import org.apache.zookeeper.KeeperException

object ZKExceptions {

    /** Wrap a block of code with lazy "call by name" semantics in a
     *  try catch block which adapts ZK exceptions to Midolman exceptions.
     *
     *  @param  block of code which can potentially throw KeeperException or
     *          InterruptedException.
     *  @return the return value of the block of code passed as argument.
     */
    def adapt[T](f: => T): T = try { f } catch {
        case e: KeeperException      => throw new StateAccessException(e)
        case e: InterruptedException => throw new StateAccessException(e)
    }

}
