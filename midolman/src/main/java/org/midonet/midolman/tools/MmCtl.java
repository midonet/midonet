/*
* Copyright 2013 Midokura Europe SARL
*/
package org.midonet.midolman.tools;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.commons.cli.*;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.host.Host;
import org.midonet.cluster.data.host.VirtualPortMapping;
import org.midonet.config.ConfigProvider;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.guice.CacheModule;
import org.midonet.midolman.guice.MidolmanModule;
import org.midonet.midolman.guice.MonitoringStoreModule;
import org.midonet.midolman.guice.cluster.DataClusterClientModule;
import org.midonet.midolman.guice.config.ConfigProviderModule;
import org.midonet.midolman.guice.serialization.SerializationModule;
import org.midonet.midolman.guice.zookeeper.ZookeeperConnectionModule;
import org.midonet.midolman.host.HostIdGenerator;
import org.midonet.midolman.host.config.HostConfig;
import org.midonet.midolman.host.guice.HostConfigProvider;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.version.guice.VersionModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;


/**
 * This class implements 'mm-ctl' command line tool.  This tool provides CLI
 * to manage Midolman.
 */
public class MmCtl {

    private static final Logger log = LoggerFactory.getLogger(MmCtl.class);

    private enum MM_CTL_RET_CODE {

        UNKNOWN_ERROR(-1, "Command failed"),
        SUCCESS(0, "Command succeeded"),
        BAD_COMMAND(1, "Invalid command"),
        HOST_ID_NOT_IN_FILE(2, "Failed to get host ID"),
        STATE_ERROR(3, "State configuration error"),
        NO_CONFIG(4, "Configuration file not found");

        private final int code;
        private final String msg;

        private MM_CTL_RET_CODE(int code, String msg) {
            this.code = code;
            this.msg = msg;
        }

        /**
         * Construct an {@link MmCtlResult} object from an
         * {@link MM_CTL_RET_CODE} object.
         *
         * @return {@link MmCtlResult} object
         */
        private MmCtlResult getResult() {
            return new MmCtlResult(this.code, this.msg);
        }

        /**
         * Construct an {@link MmCtlResult} object from an
         * {@link MM_CTL_RET_CODE} object.
         *
         * @param e Throwable object to log
         * @return {@link MmCtlResult} object
         */
        private MmCtlResult getResult(Throwable e) {
            log.error(this.msg, e);
            return new MmCtlResult(this.code, this.msg);
        }

        /**
         * Construct an {@link MmCtlResult} object from an
         * {@link MM_CTL_RET_CODE} object.
         *
         * @param msg Error message to override the default message
         * @return {@link MmCtlResult} object
         */
        private MmCtlResult getResult(String msg) {
            return new MmCtlResult(this.code, msg);
        }
    }

    private final static String DEFAULT_CONFIG_PATH =
            "/etc/midolman/midolman.conf";

    private final DataClient dataClient;
    private final HostConfig hostConfig;

    public MmCtl(DataClient dataClient, HostConfig hostConfig) {
        this.dataClient = dataClient;
        this.hostConfig = hostConfig;
    }

    private UUID getHostId() throws IOException {
        String localPropertiesFilePath =
                hostConfig.getHostPropertiesFilePath();
        return HostIdGenerator.getIdFromPropertiesFile(localPropertiesFilePath);
    }

    private MmCtlResult bindPort(UUID portId, String deviceName) {
        log.debug("MmCtl.bindPort entered. portId=" + portId + ", deviceName="
                + deviceName);

        try {
            dataClient.hostsAddVrnPortMapping(getHostId(), portId, deviceName);
        } catch (IOException e) {
            return MM_CTL_RET_CODE.HOST_ID_NOT_IN_FILE.getResult(e);
        } catch (StateAccessException e) {
            return MM_CTL_RET_CODE.STATE_ERROR.getResult(e);
        } catch (Exception e) {
            return MM_CTL_RET_CODE.UNKNOWN_ERROR.getResult(e);
        }

        return MM_CTL_RET_CODE.SUCCESS.getResult();
    }

    private MmCtlResult unbindPort(UUID portId) {
        log.debug("MmCtl.unbindPort entered. portId=" + portId);

        String errMsg = "Failed to unbind port and interface";
        try {
            dataClient.hostsDelVrnPortMapping(getHostId(), portId);
        } catch (IOException e) {
            return MM_CTL_RET_CODE.HOST_ID_NOT_IN_FILE.getResult(e);
        } catch (StateAccessException e) {
            return MM_CTL_RET_CODE.STATE_ERROR.getResult(e);
        } catch (Exception e) {
            return MM_CTL_RET_CODE.UNKNOWN_ERROR.getResult(e);
        }

        return MM_CTL_RET_CODE.SUCCESS.getResult();
    }

    private static String getConfig(CommandLine cl) {

        // First get the config file
        String configFilePath = DEFAULT_CONFIG_PATH;
        if (cl.hasOption("config")) {
            configFilePath = cl.getOptionValue("config");
        }

        if (Files.notExists(Paths.get(configFilePath))) {
            return null;
        }

        return configFilePath;
    }

    private static OptionGroup getOptionalOptionGroup() {

        OptionGroup optionalGroup = new OptionGroup();

        optionalGroup.addOption(OptionBuilder.hasArg()
                .withLongOpt("config")
                .withDescription("MM configuration file")
                .create());

        optionalGroup.setRequired(false);
        return optionalGroup;
    }

    private static OptionGroup getMutuallyExclusiveOptionGroup() {

        // The command line tool can only accept one of these options:
        OptionGroup mutuallyExclusiveOptions = new OptionGroup();

        mutuallyExclusiveOptions.addOption(OptionBuilder.hasArgs(2)
                .isRequired()
                .withLongOpt("bind-port")
                .withDescription("Bind a port to an interface")
                .create());

        mutuallyExclusiveOptions.addOption(OptionBuilder.hasArg()
                .isRequired()
                .withLongOpt("unbind-port")
                .withDescription("Unbind a port from an interface")
                .create());

        mutuallyExclusiveOptions.addOption(OptionBuilder
                .withLongOpt("list-hosts")
                .withDescription("List MidolMan agents in the system")
                .create());

        // make sure that there is at least one.
        mutuallyExclusiveOptions.setRequired(true);

        return mutuallyExclusiveOptions;
    }

    private MmCtlResult listHosts() {

        try {
            UUID myHostId = getHostId();
            String indent = "";
            for (Host h : dataClient.hostsGetAll()) {
                String thisHostMarker = "";
                if (h.getId().equals(myHostId)) {
                    thisHostMarker = "(*)";
                }
                String format = "Host: id=%s %s\n  name=%s\n  isAlive=%s";
                String output = String.format(format, h.getId(), thisHostMarker,
                        h.getName(), h.getIsAlive());
                System.out.println(output);

                indent = "  ";
                System.out.println(indent + "addresses: ");
                for (InetAddress ia : h.getAddresses()) {
                    if (!ia.isLinkLocalAddress())
                        System.out.println("    " + ia);
                }
                System.out.println(indent + "vport-host-if-bindings:");
                for (VirtualPortMapping vpm :
                        dataClient.hostsGetVirtualPortMappingsByHost(h.getId())
                        ) {
                    System.out.println(indent + indent +
                            vpm.getData().toString());
                }
                System.out.println();
            }
        } catch (StateAccessException e) {
            e.printStackTrace();
            return MM_CTL_RET_CODE.STATE_ERROR.getResult(e);
        } catch (SerializationException e) {
            e.printStackTrace();
            return MM_CTL_RET_CODE.UNKNOWN_ERROR.getResult(e);
        } catch (IOException e) {
            e.printStackTrace();
            return MM_CTL_RET_CODE.UNKNOWN_ERROR.getResult(e);
        }
        return MM_CTL_RET_CODE.SUCCESS.getResult();
    }

    private static Injector getInjector(String configFilePath) {

        AbstractModule commandModule = new AbstractModule() {

            @Override
            protected void configure() {
                requireBinding(ConfigProvider.class);
                bind(MidolmanConfig.class).toProvider(
                        MidolmanModule.MidolmanConfigProvider.class)
                        .asEagerSingleton();
                bind(HostConfig.class)
                        .toProvider(HostConfigProvider.class)
                        .asEagerSingleton();
                install(new MonitoringStoreModule());
                install(new CacheModule());
                install(new ZookeeperConnectionModule());
                install(new VersionModule());
                install(new SerializationModule());
                install(new DataClusterClientModule());
            }
        };

        return Guice.createInjector(
                new ConfigProviderModule(configFilePath),
                commandModule
        );
    }

    private static UUID getPortUuid(String val) throws ParseException {

        try {
            return UUID.fromString(val);
        } catch (IllegalArgumentException e) {
            throw new ParseException("Invalid port ID encountered");
        }
    }

    public static void main(String... args) {
        Options options = new Options();

        // Configure the CLI options
        options.addOptionGroup(getMutuallyExclusiveOptionGroup());
        options.addOptionGroup(getOptionalOptionGroup());

        CommandLineParser parser = new PosixParser();
        try {
            CommandLine cl = parser.parse(options, args);

            // First get the config file
            String configFilePath = getConfig(cl);
            if (configFilePath == null) {
                System.err.println("Config file not found.");
                System.exit(MM_CTL_RET_CODE.NO_CONFIG.code);
            }

            // Set up Guice dependencies
            Injector injector = getInjector(configFilePath);
            MmCtl mmctl = new MmCtl(injector.getInstance(DataClient.class),
                    injector.getInstance(HostConfig.class));

            MmCtlResult res = null;
            if (cl.hasOption("bind-port")) {
                String[] opts = cl.getOptionValues("bind-port");

                if (opts == null || opts.length < 2) {
                    throw new ParseException("bind-port requires two " +
                            "arguments: port ID and device name");
                }

                res = mmctl.bindPort(getPortUuid(opts[0]), opts[1]);
            } else if (cl.hasOption("unbind-port")) {
                String opt = cl.getOptionValue("unbind-port");

                res = mmctl.unbindPort(getPortUuid(opt));

            } else if (cl.hasOption("list-hosts")) {
                res = mmctl.listHosts();

            } else {
                // Only a programming error could cause this part to be
                // executed.
                throw new RuntimeException("Unknown option encountered.");
            }

            if (res.isSuccess()) {
                System.out.println(res.getMessage());
            } else {
                System.err.println(res.getMessage());
            }

            System.exit(res.getExitCode());

        } catch (ParseException e) {
            System.err.println("Error with the options: " + e.getMessage());
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("mm-ctl", options);
            System.exit(MM_CTL_RET_CODE.BAD_COMMAND.code);
        }
    }
}
