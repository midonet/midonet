package org.midonet.cluster

import scala.util.Try

import com.typesafe.config.{Config, ConfigException, ConfigFactory}
import org.slf4j.LoggerFactory

import org.midonet.cluster.ClusterBootstrap._
import org.midonet.cluster.ClusterNode.Context
import org.midonet.cluster.services.zookeeper.ZooKeeperMinion
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.conf.{ResourceConf, LegacyConf, MidoNodeConfigurator}

/** This class provides the necessary configuration for a MidoNet cluster to
  * bootstrap and start.  The job here involves figuring out what's the user's
  * intent based on the config files provided (or not), preparing a
  * configuration that starts a cluster with the settings detailed on these
  * files, or instead assume a self-contained cluster with default settings.
  */
object ClusterBootstrap {

    private val log = LoggerFactory.getLogger("org.midonet.cluster.bootstrap")

    // This configuration will be used when the node is started without any
    // bootstrap file, in which case the node will start an embedded 1-node
    // ZK cluster by itself.
    val EMBEDDED_BOOTSTRAP_CONF = ConfigFactory.parseString(
        """
          |zookeeper {
          |     zookeeper_hosts : "0.0.0.0:2181"
          |     root_key : "/midonet/v2"
          |     data_dir : "/tmp/midonet/zk/data"
          |     log_dir : "/tmp/midonet/zk/log"
          |}
        """.stripMargin
    )

    /**
     * For convenience. Takes an array of command line parameters and will
     * provide a bootstrap instance with a config file if provided, or self
     * contained.  The first argument will be taken as config file path.
     */
    def fromCommandLine(args: Array[String]): Try[ClusterBootstrap] = {
        Try {
            if(args.length > 0)
                providingInitFile(args(0))
            else
                selfContained()
        }
    }

    /** Provides a [[ClusterBootstrap]] instance taking parameters from a
      * file at the given path.
      */
    def providingInitFile(confFilePath: String): ClusterBootstrap = {
        log info s"Loading configuration: $confFilePath"
        new ClusterBootstrap(new LegacyConf(confFilePath).get)
    }

    /** Provides a [[ClusterBootstrap]] instance assuming the default
      * parameters defined at [[EMBEDDED_BOOTSTRAP_CONF]].
      */
    def selfContained(): ClusterBootstrap = {
        new ClusterBootstrap(EMBEDDED_BOOTSTRAP_CONF)
    }
}

/** This class prepares the base infrastructure required by the MidoNet Cluster
  * executions service to start up.  This includes for example ensuring that
  * a ZooKeeper cluster is available (whether embedded or external).
  */
class ClusterBootstrap(conf: Config) {

    private val bootstrapConf = new MidonetBackendConfig(conf)
    private var configurator: MidoNodeConfigurator = _
    private var zkMinion: ZooKeeperMinion = _

    /** Perform all bootstrap tasks for the node defined by nodeContext, and
      * block until all are completed.  Upon successful return, this Bootstrap
      * instance guarantees that:
      * - All services required for bootstrapping the cluster are ready (at
      *   the moment this is only ZooKeeper).
      * - The Cluster full configuration is loaded and new schemas deployed into
      *   the ZooKeeper cluster.  This runtime config can be accessed via
      *   [[runtimeConfig]].
      */
    def startAndWait(nodeContext: Context): Unit = {

        // Curator defines this in a static variable, so we need to
        // prepare the property before any instance is loaded.
        try {
            System.setProperty("jute.maxbuffer",
                               Integer.toString(bootstrapConf.bufferSize))
        } catch {
            case _: ConfigException => // ok, it'll use a default
        }

        zkMinion = new ZooKeeperMinion(nodeContext, bootstrapConf)
        zkMinion.startAsync()
        zkMinion.awaitRunning()
        configurator = MidoNodeConfigurator(conf)

        if (configurator.deployBundledConfig()) {
            log.info("Deployed new configuration schema into NSDB")
        }
        configurator.centralPerNodeConfig(nodeContext.nodeId)
    }

    /**
     * Expose the runtime config after all bootstrap tasks have been
     * performed and the new schemas have been merged and deployed (if
     * applicable) into the NSDB.
     *
     * IMPORTANT: this method may only be called only after [[startAndWait]]
     * has been successfully invoked, or it will NPE.
     */
    def runtimeConfig: Config = {
        conf.withFallback(
            new ResourceConf(s"org/midonet/conf/schemas/nsdb.conf").get).withFallback(
            new ResourceConf(s"org/midonet/conf/schemas/cluster.conf").get).withFallback(
                MidoNodeConfigurator.bootstrapConfig())
    }

    /** Use after all cluster services have been stopped */
    def tearDown(): Unit = {
        if (zkMinion != null && zkMinion.isRunning) {
            zkMinion.stopAsync().awaitTerminated()
        }
    }
}
