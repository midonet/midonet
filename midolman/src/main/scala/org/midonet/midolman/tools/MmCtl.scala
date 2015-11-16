/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.midolman.tools

import java.util.UUID
import java.util.concurrent.TimeUnit

import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

import com.google.inject.{Guice, Injector}
import com.sun.security.auth.module.UnixSystem

import org.apache.commons.cli._
import org.apache.curator.framework.CuratorFramework

import org.midonet.cluster.ZookeeperLockFactory
import org.midonet.cluster.backend.zookeeper.StateAccessException
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Topology
import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.storage.{MidonetBackendConfig, MidonetBackendModule}
import org.midonet.cluster.util.UUIDUtil
import org.midonet.conf.{HostIdGenerator, MidoNodeConfigurator}
import org.midonet.midolman.cluster.LegacyClusterModule
import org.midonet.midolman.cluster.serialization.SerializationModule
import org.midonet.midolman.cluster.zookeeper.ZookeeperConnectionModule
import org.midonet.midolman.state.ZookeeperConnectionWatcher
import org.midonet.util.concurrent.toFutureOps


object MmCtlResult {

    sealed abstract class MmCtlRetCode(code: Int, msg: String)
        extends Ordered[MmCtlRetCode] {

        def compare(that: MmCtlRetCode) = this.code - that.getCode
        def getCode = this.code
        def getMessage = this.msg
    }

    case object UNKNOWN_ERROR extends MmCtlRetCode(-1, "Command failed")
    case object SUCCESS extends MmCtlRetCode(0, "Command succeeded")
    case class BAD_COMMAND(detailMsg: String)
        extends MmCtlRetCode(1, "Invalid command: " + detailMsg)
    case object HOST_ID_NOT_FOUND
        extends MmCtlRetCode(2, "Failed to get host ID")
    case object STATE_ERROR extends MmCtlRetCode(
        3, "State configuration error")
    case object PERMISSION_DENIED extends MmCtlRetCode(13, "Permission denied")
}

trait PortBinder {

    def bindPort(portId: UUID, hostId: UUID, deviceName: String)
    def unbindPort(portId: UUID, hostId: UUID)

    def bindPort(portId: UUID, deviceName: String): Unit = {
        bindPort(portId, HostIdGenerator.getHostId, deviceName)
    }

    def unbindPort(portId: UUID): Unit = {
        unbindPort(portId, HostIdGenerator.getHostId)
    }
}

class ZoomPortBinder(storage: Storage,
                     lockFactory: ZookeeperLockFactory) extends PortBinder {

    private val LockWaitSec = 5

    private def getPortBuilder(portId: UUID): Topology.Port.Builder =
        storage.get(classOf[Topology.Port], portId).await().toBuilder

    override def bindPort(portId: UUID, hostId: UUID,
                          deviceName: String): Unit = {

        val lock = lockFactory.createShared(
            ZookeeperLockFactory.ZOOM_TOPOLOGY)
        lock.acquire(LockWaitSec, TimeUnit.SECONDS)

        try {
            val p = getPortBuilder(portId)
                         .setHostId(UUIDUtil.toProto(hostId))
                         .setInterfaceName(deviceName)
                         .build()
            storage.update(p)
        } finally {
            lock.release()
        }
    }

    override def unbindPort(portId: UUID, hostId: UUID): Unit = {

        val lock = lockFactory.createShared(
            ZookeeperLockFactory.ZOOM_TOPOLOGY)
        lock.acquire(LockWaitSec, TimeUnit.SECONDS)

        try {
            val pHostId = UUIDUtil.toProto(hostId)
            val p = storage.get(classOf[Port], portId).await()

            // Unbind only if the port is currently bound to an interface on
            // the same host.  This is necessary since in Nova, bind on the
            // new host happens before unbind on live migration, and we don't
            // want remove the existing binding on the new host.
            if (p.hasHostId && pHostId == p.getHostId) {
                val p = getPortBuilder(portId)
                              .clearHostId()
                              .clearInterfaceName()
                              .build()
                storage.update(p)
            }
        } finally {
            lock.release()
        }
    }
}

class MmCtl(injector: Injector) {

    import MmCtlResult._

    val portBinder = getPortBinder(injector)

    private def handleResult(f: => Unit): MmCtlRetCode = {

        try {
            f
            SUCCESS
        } catch {
            case e: HostIdGenerator.PropertiesFileNotWritableException =>
                HOST_ID_NOT_FOUND
            case e: StateAccessException =>
                STATE_ERROR
            case NonFatal(t) =>
                t.printStackTrace(System.err)
                UNKNOWN_ERROR
        }
    }

    private def getPortBinder(injector: Injector): PortBinder = {
        val curator = injector.getInstance(classOf[CuratorFramework])
        curator.start()

        val backend = injector.getInstance(classOf[MidonetBackend])
        MidonetBackend.setupBindings(backend.store, backend.stateStore)

        val lockFactory = injector.getInstance(classOf[ZookeeperLockFactory])
        new ZoomPortBinder(backend.store, lockFactory)
    }

    def bindPort(portId: UUID, deviceName: String): MmCtlRetCode = {
        handleResult(portBinder.bindPort(portId, deviceName))
    }

    def unbindPort(portId: UUID): MmCtlRetCode = {
        handleResult(portBinder.unbindPort(portId))
    }
}

object MmCtl {

    import MmCtlResult._

    // For backward compatibility, if the legacy midolman.conf exists on the
    // host that mm-ctl runs, use it as its primary configuration source.
    val LegacyConfFilePath = "/etc/midolman/midolman.conf"

    def getInjector: Injector = {
        val configurator = MidoNodeConfigurator.apply(LegacyConfFilePath)
        val config = new MidonetBackendConfig(configurator.runtimeConfig)
        Guice.createInjector(new MidonetBackendModule(config),
                             new ZookeeperConnectionModule(
                                 classOf[ZookeeperConnectionWatcher]),
                             new SerializationModule,
                             new LegacyClusterModule)
    }

    def getMutuallyExclusiveOptionGroup: OptionGroup = {

        val mutuallyExclusiveOptions = new OptionGroup

        OptionBuilder.hasArgs(2)
        OptionBuilder.isRequired
        OptionBuilder.withLongOpt("bind-port")
        OptionBuilder.withDescription("Bind a port to an interface")
        mutuallyExclusiveOptions.addOption(OptionBuilder.create)

        OptionBuilder.hasArg
        OptionBuilder.isRequired
        OptionBuilder.withLongOpt("unbind-port")
        OptionBuilder.withDescription("Unbind a port from an interface")
        mutuallyExclusiveOptions.addOption(OptionBuilder.create)

        mutuallyExclusiveOptions.setRequired (true)
        mutuallyExclusiveOptions

        // TODO: add a debug mode
    }

    def checkUserAccess() = {
        if (new UnixSystem().getUid != 0) {
            System.err.println("This command should be executed by root.")
            System.exit(PERMISSION_DENIED.getCode)
        }
    }

    def main(args: Array[String]): Unit = {

        checkUserAccess()

        // Configure the CLI options
        val options = new Options
        options.addOptionGroup(getMutuallyExclusiveOptionGroup)

        val parser = new PosixParser
        val cl = parser.parse(options, args)
        val mmctl = new MmCtl(getInjector)

        val res: MmCtlRetCode = if (cl.hasOption("bind-port")) {
            val opts = cl.getOptionValues("bind-port")

            if (opts != null && opts.length >= 2) {
                Try(UUID.fromString(opts(0))) match {
                    case Success(portId) => mmctl.bindPort(portId, opts(1))
                    case Failure(_) => BAD_COMMAND("Invalid port ID specified")
                }
            } else {
                BAD_COMMAND("bind-port requires port ID and device name")
            }

        } else if (cl.hasOption("unbind-port")) {

            val opt = cl.getOptionValue("unbind-port")

            Try(UUID.fromString(opt)) match {
                case Success(portId) => mmctl.unbindPort(portId)
                case Failure(_) => BAD_COMMAND("Invalid port ID specified")
            }
        } else {
            BAD_COMMAND("Unrecognized command")
        }

        res match {
            case SUCCESS =>
                System.out.println(res.getMessage)

            case BAD_COMMAND(msg) =>
                System.err.println(res.getMessage)
                val formatter = new HelpFormatter
                formatter.printHelp("mm-ctl", options)

            case _ =>
                System.err.println(res.getMessage)
        }

        System.exit(res.getCode)
    }
}
