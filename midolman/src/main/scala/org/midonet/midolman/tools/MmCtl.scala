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


sealed class MmCtlResult(exitCode: Int, message: String) {
    def getExitCode: Int = this.exitCode
    def isSuccess = this.exitCode == 0
    def getMessage = this.message
}

object MmCtlResult {

    sealed abstract class MmCtlRetCode(code: Int, msg: String)
        extends Ordered[MmCtlRetCode] {

        def compare(that: MmCtlRetCode) = this.code - that.getCode
        def getResult = new MmCtlResult(this.code, this.msg)
        def getCode = this.code
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

    import MmCtlResult._

    def bindPort(portId: UUID, hostId: UUID, deviceName: String): MmCtlResult
    def unbindPort(portId: UUID, hostId: UUID): MmCtlResult

    def bindPort(portId: UUID, deviceName: String): MmCtlResult = {
        var hostId: UUID = null
        try {
            hostId = HostIdGenerator.getHostId
        } catch {
            case e: HostIdGenerator.PropertiesFileNotWritableException =>
                return HOST_ID_NOT_FOUND.getResult
            case _: Exception =>
                return UNKNOWN_ERROR.getResult
        }

        bindPort(portId, hostId, deviceName)
    }

    def unbindPort(portId: UUID): MmCtlResult = {
        var hostId: UUID = null
        try {
            hostId = HostIdGenerator.getHostId
        } catch {
            case e: HostIdGenerator.PropertiesFileNotWritableException =>
                return HOST_ID_NOT_FOUND.getResult
            case _: Exception =>
                return UNKNOWN_ERROR.getResult
        }

        unbindPort(portId, hostId)
    }
}

class DataClientPortBinder(dataClient: DataClient) extends PortBinder {

    import MmCtlResult._

    override def bindPort(portId: UUID, hostId: UUID,
                          deviceName: String): MmCtlResult = {
        try {
            dataClient.hostsAddVrnPortMapping(hostId, portId, deviceName)
        } catch {
            case e: StateAccessException =>
                return STATE_ERROR.getResult
            case _: Exception =>
                return UNKNOWN_ERROR.getResult
        }
        SUCCESS.getResult
    }

    override def unbindPort(portId: UUID, hostId: UUID): MmCtlResult = {
        try {
            dataClient.hostsDelVrnPortMapping(hostId, portId)
        } catch {
            case e: StateAccessException =>
                return STATE_ERROR.getResult
            case _: Exception =>
                return UNKNOWN_ERROR.getResult
        }
        SUCCESS.getResult
    }
}

class ZoomPortBinder(storage: Storage) extends PortBinder {

    import MmCtlResult._

    override def bindPort(portId: UUID, hostId: UUID,
                          deviceName: String): MmCtlResult = {
        val builder = storage.get(classOf[Topology.Port],
                                  portId).await().toBuilder
        val port = builder.setHostId(
            UUIDUtil.toProto(hostId)).setInterfaceName(deviceName).build()
        storage.update(port)
        SUCCESS.getResult
    }

    override def unbindPort(portId: UUID, hostId: UUID): MmCtlResult = {
        val builder = storage.get(classOf[Topology.Port],
                                  portId).await().toBuilder
        val port = builder.clearHostId().clearInterfaceName().build()
        storage.update(port)
        SUCCESS.getResult
    }
}

class MmCtl(injector: Injector) {

    val portBinder = getPortBinder(injector)

    def getPortBinder(injector: Injector): PortBinder = {
        val dataClient = injector.getInstance(classOf[DataClient])
        val backend = injector.getInstance(classOf[MidonetBackend])
        val config = injector.getInstance(classOf[MidolmanConfig])

        // TODO: Change 'neutron' to 'cluster'
        if (config.neutron.enabled)
            new ZoomPortBinder(backend.store)
        else
            new DataClientPortBinder(dataClient)
    }

    def bindPort(portId: UUID, deviceName: String): MmCtlResult = {
        portBinder.bindPort(portId, deviceName)
    }

    def unbindPort(portId: UUID): MmCtlResult = {
        portBinder.unbindPort(portId)
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

    def handleBadCommand(options: Options, msg: String) = {
        System.err.println("Error with the options: " + msg)
        val formatter = new HelpFormatter
        formatter.printHelp("mm-ctl", options)
        System.exit(BAD_COMMAND.getCode)
    }

    def main(args: Array[String]): Unit = {

        checkUserAccess()

        // Configure the CLI options
        val options = new Options
        options.addOptionGroup(getMutuallyExclusiveOptionGroup)

        val parser = new PosixParser
        val cl = parser.parse(options, args)
        val mmctl = new MmCtl(getInjector)
        var res: MmCtlResult = null
        var portId: UUID = null

        if (cl.hasOption("bind-port")) {
            val opts = cl.getOptionValues("bind-port")
            if (opts == null || opts.length < 2) {
                val msg = "bind-port requires port ID and device name"
                handleBadCommand(options, msg)
            }

            try {
                portId = UUID.fromString(opts(0))
            } catch {
                case e: IllegalArgumentException =>
                    val msg = "Invalid port Id specified"
                    handleBadCommand(options, msg)
            }

            res = mmctl.bindPort(portId, opts(1))
        } else if (cl.hasOption("unbind-port")) {

            val opt = cl.getOptionValue("unbind-port")
            try {
                portId = UUID.fromString(opt)
            } catch {
                case e: IllegalArgumentException =>
                    val msg = "Invalid port Id specified"
                    handleBadCommand(options, msg)
            }

            res = mmctl.unbindPort(portId)
        } else {
            throw new RuntimeException("Unknown option encountered.")
        }

        if (res.isSuccess)
            System.out.println(res.getMessage)
        else
            System.err.println(res.getMessage)

        System.exit(res.getExitCode)
    }
}
