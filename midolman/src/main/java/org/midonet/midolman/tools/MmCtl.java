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
package org.midonet.midolman.tools;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.sun.security.auth.module.UnixSystem;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.OptionGroup;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.midonet.conf.MidoNodeConfigurator;
import org.midonet.conf.MidoTestConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.host.Host;
import org.midonet.cluster.data.host.VirtualPortMapping;
import org.midonet.conf.HostIdGenerator;
import org.midonet.midolman.cluster.LegacyClusterModule;
import org.midonet.midolman.cluster.serialization.SerializationModule;
import org.midonet.midolman.cluster.zookeeper.ZookeeperConnectionModule;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.guice.config.MidolmanConfigModule;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;


/**
 * This class implements 'mm-ctl' command line tool.  This tool provides CLI
 * to manage Midolman.
 */
public class MmCtl {

    private static final Logger log = LoggerFactory.getLogger(MmCtl.class);

    private enum TASK_TYPE {
        CREATE("CREATE"),
        DELETE("DELETE");

        private final String val;

        private TASK_TYPE(String val) {
            this.val = val;
        }
    }

    private enum DATA_TYPE {
        PORT_BINDING("PORTBINDING");

        private final String val;

        private DATA_TYPE(String val) { this.val = val; }
    }

    private final String INSERT_TASK =
        "insert into midonet_tasks values (default, ?, ?, ?, ?, ?, ?, ?)";

    private enum MM_CTL_RET_CODE {

        UNKNOWN_ERROR(-1, "Command failed"),
        SUCCESS(0, "Command succeeded"),
        BAD_COMMAND(1, "Invalid command"),
        HOST_ID_NOT_IN_FILE(2, "Failed to get host ID"),
        STATE_ERROR(3, "State configuration error"),
        NO_CONFIG(4, "Configuration file not found"),
        PERMISSION_DENIED(13, "Permission denied");

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
    private final MidolmanConfig config;

    public MmCtl(DataClient dataClient, MidolmanConfig config) {
        this.dataClient = dataClient;
        this.config = config;
    }

    private UUID getHostId() throws IOException {
        return HostIdGenerator.getIdFromPropertiesFile();
    }

    private MmCtlResult bindPort(UUID portId, String deviceName) {
        log.debug("MmCtl.bindPort entered. portId=" + portId + ", deviceName="
                + deviceName);

        UUID hostId;
        try {
            hostId = getHostId();
        } catch (IOException e) {
            return MM_CTL_RET_CODE.HOST_ID_NOT_IN_FILE.getResult(e);
        }

        if (config.neutron().enabled()) {
            createBindEntries(portId, deviceName, hostId);
        } else {
            try {
                dataClient.hostsAddVrnPortMapping(hostId, portId, deviceName);
            } catch (StateAccessException e) {
                return MM_CTL_RET_CODE.STATE_ERROR.getResult(e);
            } catch (Exception e) {
                return MM_CTL_RET_CODE.UNKNOWN_ERROR.getResult(e);
            }
        }

        return MM_CTL_RET_CODE.SUCCESS.getResult();
    }

    private String getPortBindingData(UUID bindingId, UUID portId,
                                      String deviceName, UUID hostId) {
        JsonNodeFactory jnf = JsonNodeFactory.instance;
        ObjectNode json = jnf.objectNode();
        json.put("id", bindingId.toString());
        json.put("host_id", hostId.toString());
        json.put("interface_name", deviceName);
        json.put("port_id", portId.toString());
        return json.toString();
    }

    private Connection connectToDatabase() {
        try {
            //TODO: support other drivers.
            Class.forName("com.mysql.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            log.error("Driver class not available");
            throw new RuntimeException(e);
        }

        try {
            Connection connection =
                DriverManager.getConnection(
                        "jdbc:" + config.neutron().tasksDb());
            try {
                PreparedStatement ps = connection.prepareStatement("BEGIN");
                ps.executeUpdate();
                return connection;
            } catch (SQLException e) {
                failCloseConnection(connection);
                throw new RuntimeException(e);
            }
        } catch (SQLException e) {

            log.error("Could not connect to database: " +
                      config.neutron().tasksDb());
            throw new RuntimeException(e);
        }
    }

    private void failCloseConnection(Connection connection) {
        try {
            PreparedStatement ps = connection.prepareStatement("ROLLBACK");
            ps.executeUpdate();
            connection.close();
        } catch (SQLException e) {
            // we are in deep trouble now. We not only are failing to close
            // the connection when an error happened, but we can not even
            // rollback the transaction
            log.error("UNABLE TO CLOSE CONNECTION OR ROLLBACK CHANGES!!");
            throw new RuntimeException(e);
        }
    }

    private void successCloseConnection(Connection connection) {
        try {
            PreparedStatement ps = connection.prepareStatement("COMMIT");
            ps.executeUpdate();
            connection.close();
        } catch (SQLException e) {
            log.error("Unable to close connection");
            throw new RuntimeException(e);
        }
    }

    private void insertTask(Connection connect, UUID bindingId,
                            TASK_TYPE taskType, UUID portId,
                            String deviceName, UUID hostId)
            throws SQLException {
        PreparedStatement ps = connect.prepareStatement(INSERT_TASK);
        ps.setString(1, getPortBindingData(bindingId, portId, deviceName,
                                           hostId));
        ps.setString(2, bindingId.toString()); //resource id
        ps.setString(3, UUID.randomUUID().toString()); // transaction id
        ps.setDate(4, new java.sql.Date(new Date().getTime()));
        ps.setString(5, hostId.toString()); //tenant id
        ps.setString(6, DATA_TYPE.PORT_BINDING.val);
        ps.setString(7, taskType.val);
        ps.executeUpdate();
    }

    private void createBindEntries(UUID portId, String deviceName,
                                   UUID hostId) {
        Connection connect = connectToDatabase();
        try {
            insertTask(connect, portId, TASK_TYPE.CREATE, portId, deviceName,
                       hostId);
        } catch (SQLException e) {
            log.error("Failed to create port binding entries");
            failCloseConnection(connect);
            throw new RuntimeException(e);
        }
        successCloseConnection(connect);
    }

    private void removeBindEntries(UUID portId, UUID hostId) {
        Connection connect = connectToDatabase();
        try {
            insertTask(connect, portId, TASK_TYPE.DELETE, portId, null,
                       hostId);
        } catch (SQLException e) {
            failCloseConnection(connect);
            log.error("removeBindEntries failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
        successCloseConnection(connect);
    }

    private MmCtlResult unbindPort(UUID portId) {
        log.debug("MmCtl.unbindPort entered. portId=" + portId);

        UUID hostId;
        try {
            hostId = getHostId();
        } catch (IOException e) {
            return MM_CTL_RET_CODE.HOST_ID_NOT_IN_FILE.getResult(e);
        }

        if (config.neutron().enabled()) {
            removeBindEntries(portId, hostId);
        } else {
            try {
                dataClient.hostsDelVrnPortMapping(hostId, portId);
            } catch (StateAccessException e) {
                return MM_CTL_RET_CODE.STATE_ERROR.getResult(e);
            } catch (Exception e) {
                return MM_CTL_RET_CODE.UNKNOWN_ERROR.getResult(e);
            }
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

        OptionBuilder.hasArg();
        OptionBuilder.withLongOpt("config");
        OptionBuilder.withDescription("MM configuration file");
        optionalGroup.addOption(OptionBuilder.create());

        optionalGroup.setRequired(false);
        return optionalGroup;
    }

    private static OptionGroup getMutuallyExclusiveOptionGroup() {

        // The command line tool can only accept one of these options:
        OptionGroup mutuallyExclusiveOptions = new OptionGroup();

        OptionBuilder.hasArgs(2);
        OptionBuilder.isRequired();
        OptionBuilder.withLongOpt("bind-port");
        OptionBuilder.withDescription("Bind a port to an interface");
        mutuallyExclusiveOptions.addOption(OptionBuilder.create());

        OptionBuilder.hasArg();
        OptionBuilder.isRequired();
        OptionBuilder.withLongOpt("unbind-port");
        OptionBuilder.withDescription("Unbind a port from an interface");
        mutuallyExclusiveOptions.addOption(OptionBuilder.create());

        OptionBuilder.withLongOpt("list-hosts");
        OptionBuilder.withDescription("List MidolMan agents in the system");
        mutuallyExclusiveOptions.addOption(OptionBuilder.create());

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
                install(new ZookeeperConnectionModule(
                    ZookeeperConnectionWatcher.class
                ));
                bind(MidolmanConfig.class).toInstance(
                        MidolmanConfig.apply());
                install(new ZookeeperConnectionModule(
                        ZookeeperConnectionWatcher.class));
                install(new SerializationModule());
                install(new LegacyClusterModule());
            }
        };

        MidoNodeConfigurator configurator =
                MidoNodeConfigurator.forAgents(configFilePath);
        return Guice.createInjector(
                new MidolmanConfigModule(configurator),
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
        if (new UnixSystem().getUid() != 0) {
            System.err.println("This command should be executed by root.");
            System.exit(MM_CTL_RET_CODE.PERMISSION_DENIED.code);
        }

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
                    injector.getInstance(MidolmanConfig.class));

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
