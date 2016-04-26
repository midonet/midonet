package org.midonet.cluster

import java.util.UUID

/**
  * Created by xleon on 26/04/16.
  */
package object services {

    /** Encapsulates node-wide context that might be of use for minions
      *
      * @param nodeId the UUID of this Cluster node
      */
    case class Context(nodeId: UUID)

    /** Base exception for all MidoNet Minions errors. */
    class MinionException(msg: String, cause: Throwable)
        extends Exception(msg, cause) {}

    /** Loggers */
    final val MinionDaemonLog = "org.midonet.minion"

}
