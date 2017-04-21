/*
 * Copyright 2016 Midokura SARL
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

import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

import com.codahale.metrics.MetricRegistry
import com.google.inject.{Guice, Injector}
import com.sun.security.auth.module.UnixSystem
import org.apache.commons.cli._

import org.midonet.cluster.backend.zookeeper.{StateAccessException, ZookeeperConnectionWatcher}
import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.storage.{MidonetBackendConfig, MidonetBackendModule}
import org.midonet.conf.{HostIdGenerator, MidoNodeConfigurator}
import org.midonet.midolman.cluster.zookeeper.ZookeeperConnectionModule
import org.midonet.services.rest_api.PortBinder

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
        val backend = injector.getInstance(classOf[MidonetBackend])
        val curator = backend.curator
        curator.start()

        MidonetBackend.setupBindings(backend.store, backend.stateStore)

        new PortBinder(backend.store, backend.stateStore)
    }

    def bindPort(portId: UUID, deviceName: String): MmCtlRetCode = {
        handleResult(portBinder.bindPort(
                         portId, HostIdGenerator.getHostId, deviceName))
    }

    def unbindPort(portId: UUID): MmCtlRetCode = {
        handleResult(portBinder.unbindPort(portId,
                                           HostIdGenerator.getHostId))
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
        Guice.createInjector(new MidonetBackendModule(config, None,
                                                      new MetricRegistry),
                             new ZookeeperConnectionModule(
                                 classOf[ZookeeperConnectionWatcher]))
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
