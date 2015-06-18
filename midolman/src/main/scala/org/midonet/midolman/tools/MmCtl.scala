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

import com.google.inject.{Guice, Injector}
import com.sun.security.auth.module.UnixSystem
import org.apache.commons.cli._
import org.midonet.cluster.DataClient
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Topology
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.storage.MidonetBackendModule
import org.midonet.cluster.util.UUIDUtil
import org.midonet.conf.{HostIdGenerator, MidoNodeConfigurator}
import org.midonet.midolman.cluster.LegacyClusterModule
import org.midonet.midolman.cluster.serialization.SerializationModule
import org.midonet.midolman.cluster.zookeeper.ZookeeperConnectionModule
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.guice.config.MidolmanConfigModule
import org.midonet.midolman.state.{StateAccessException, ZookeeperConnectionWatcher}
import org.midonet.util.concurrent.toFutureOps

import scala.util.{Failure, Success, Try}


object MmCtlResult {

    sealed abstract class MmCtlRetCode(code: Int, msg: String)
        extends Ordered[MmCtlRetCode] {

        def compare(that: MmCtlRetCode) = this.code - that.getCode
        def getCode = this.code
        def getMessage = this.msg
    }

    case object UNKNOWN_ERROR extends MmCtlRetCode(-1, "Command failed")
    case object SUCCESS extends MmCtlRetCode(0, "Command succeeded")
    case object BAD_COMMAND extends MmCtlRetCode(1, "Invalid command")
    case object HOST_ID_NOT_FOUND extends MmCtlRetCode(2,
                                                       "Failed to get host ID")
    case object STATE_ERROR extends MmCtlRetCode(3,
                                                 "State configuration error")
    case object PERMISSION_DENIED extends MmCtlRetCode(13, "Permission denied")
}

trait PortBinder {

    def bindPort(portId: UUID, hostId: UUID, deviceName: String): Try[Unit]
    def unbindPort(portId: UUID, hostId: UUID): Try[Unit]

    private def tryGetHostId(): Try[UUID] =
        Try { HostIdGenerator.getHostId }

    def bindPort(portId: UUID, deviceName: String): Try[Unit] = {
        tryGetHostId().flatMap { h => bindPort(portId, h, deviceName) }
    }

    def unbindPort(portId: UUID): Try[Unit] = {
        tryGetHostId().flatMap { h => unbindPort(portId, h) }
    }
}

class DataClientPortBinder(dataClient: DataClient) extends PortBinder {

    override def bindPort(portId: UUID, hostId: UUID,
                          deviceName: String): Try[Unit] = {
        Try(dataClient.hostsAddVrnPortMapping(hostId, portId, deviceName))
    }

    override def unbindPort(portId: UUID, hostId: UUID): Try[Unit] = {
        Try(dataClient.hostsDelVrnPortMapping(hostId, portId))
    }
}

class ZoomPortBinder(storage: Storage) extends PortBinder {

    private def getPortBuilder(portId: UUID): Topology.Port.Builder =
        storage.get(classOf[Topology.Port], portId).await().toBuilder

    override def bindPort(portId: UUID, hostId: UUID,
                          deviceName: String): Try[Unit] = {

        Try(getPortBuilder(portId)
                .setHostId(UUIDUtil.toProto(hostId))
                .setInterfaceName(deviceName)
                .build())
            .flatMap { p => Try(storage.update(p)) }
    }

    override def unbindPort(portId: UUID, hostId: UUID): Try[Unit] = {

        Try(getPortBuilder(portId)
                .clearHostId()
                .clearInterfaceName()
                .build())
            .flatMap { p => Try(storage.update(p)) }
    }
}

class MmCtl(injector: Injector) {

    import MmCtlResult._

    val portBinder = getPortBinder(injector)

    private def handleResult(res: Try[Unit]): MmCtlRetCode = {

        res recover {
            case e: HostIdGenerator.PropertiesFileNotWritableException =>
                return HOST_ID_NOT_FOUND
            case e: StateAccessException =>
                return STATE_ERROR
            case e: Exception =>
                e.printStackTrace(System.err)
                return UNKNOWN_ERROR
        }
        SUCCESS
    }

    private def getPortBinder(injector: Injector): PortBinder = {
        val dataClient = injector.getInstance(classOf[DataClient])
        val backend = injector.getInstance(classOf[MidonetBackend])
        val config = injector.getInstance(classOf[MidolmanConfig])

        // TODO: Change 'neutron' to 'cluster'
        if (config.neutron.enabled)
            new ZoomPortBinder(backend.store)
        else
            new DataClientPortBinder(dataClient)
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

    def getInjector: Injector = {
        val configurator: MidoNodeConfigurator = MidoNodeConfigurator.apply()
        val config: MidolmanConfig = MidolmanConfigModule
            .createConfig(configurator)
        Guice.createInjector(new MidolmanConfigModule(config),
                             new MidonetBackendModule(config.zookeeper),
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
        var res: MmCtlRetCode = null
        var cmdErrMsg: String = null

        if (cl.hasOption("bind-port")) {
            val opts = cl.getOptionValues("bind-port")

            if (opts != null && opts.length >= 2) {

                res = Try(UUID.fromString(opts(0))) match {
                    case Success(p) =>
                        mmctl.bindPort(p, opts(1))
                    case Failure(e) =>
                        cmdErrMsg = "Invalid port ID specified"
                        BAD_COMMAND
                }

            } else {
                cmdErrMsg = "bind-port requires port ID and device name"
                res = BAD_COMMAND
            }

        } else if (cl.hasOption("unbind-port")) {

            val opt = cl.getOptionValue("unbind-port")

            res = Try(UUID.fromString(opt)) match {
                case Success(p) =>
                    mmctl.unbindPort(p)
                case Failure(e) =>
                    cmdErrMsg = "Invalid port ID specified"
                    BAD_COMMAND
            }
        } else {
            res = UNKNOWN_ERROR
        }

        res match {

            case SUCCESS =>
                System.out.println(res.getMessage)

            case BAD_COMMAND =>
                System.err.println(res.getMessage)
                if (cmdErrMsg != null)
                    System.err.println(cmdErrMsg)
                val formatter = new HelpFormatter
                formatter.printHelp("mm-ctl", options)

            case _ =>
                System.err.println(res.getMessage)
        }

        System.exit(res.getCode)
    }
}
