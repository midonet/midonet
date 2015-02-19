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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;
import java.util.UUID;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

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
import org.codehaus.jackson.node.JsonNodeFactory;
import org.codehaus.jackson.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.host.Host;
import org.midonet.cluster.data.host.VirtualPortMapping;
import org.midonet.config.ConfigProvider;
import org.midonet.config.HostIdGenerator;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.guice.MidolmanModule;
import org.midonet.midolman.guice.cluster.DataClusterClientModule;
import org.midonet.midolman.guice.config.ConfigProviderModule;
import org.midonet.midolman.guice.serialization.SerializationModule;
import org.midonet.midolman.guice.zookeeper.ZookeeperConnectionModule;
import org.midonet.midolman.host.config.HostConfig;
import org.midonet.midolman.host.guice.HostConfigProvider;
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
        CREATE_TASK_TYPE(1),
        DELETE_TASK_TYPE(2);

        private final int id;

        private TASK_TYPE(int id) {
            this.id = id;
        }
    }

    private enum DATA_TYPE {
        HOST_BINDING_DATA_TYPE(12);

        private final int id;

        private DATA_TYPE(int id) { this.id = id; }
    }

    private final String INSERT_TASK =
        "insert into midonet_tasks values (default, ?, ?, ?, ?, ?, ?, ?)";

    private final String INSERT_PORT_BINDING =
        "insert into midonet_port_binding values (?, ?, ?, ?)";

    private final String DELETE_PORT_BINDING =
        "delete from midonet_port_binding where id = ?";

    private final String PORT_BINDING_QUERY =
        "select id from midonet_port_binding where port_id = ?";

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
    private final HostConfig hostConfig;
    private final MidolmanConfig midolmanConfig;

    public MmCtl(DataClient dataClient, HostConfig hostConfig,
                 MidolmanConfig midolmanConfig) {
        this.dataClient = dataClient;
        this.hostConfig = hostConfig;
        this.midolmanConfig = midolmanConfig;
    }

    private UUID getHostId() throws IOException {
        String localPropertiesFilePath =
                hostConfig.getHostPropertiesFilePath();
        return HostIdGenerator.getIdFromPropertiesFile(localPropertiesFilePath);
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

        try {
            dataClient.hostsAddVrnPortMapping(hostId, portId, deviceName);
        } catch (StateAccessException e) {
            return MM_CTL_RET_CODE.STATE_ERROR.getResult(e);
        } catch (Exception e) {
            return MM_CTL_RET_CODE.UNKNOWN_ERROR.getResult(e);
        }

        createBindEntries(portId, deviceName, hostId);

        return MM_CTL_RET_CODE.SUCCESS.getResult();
    }

    private String getPortBindingData(UUID portId, UUID bindingId,
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
                    "jdbc:" + midolmanConfig.getTasksDbConn());
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
                      midolmanConfig.getTasksDbConn());
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
        ps.setInt(1, taskType.id);
        ps.setInt(2, DATA_TYPE.HOST_BINDING_DATA_TYPE.id);
        ps.setString(3, getPortBindingData(bindingId, portId, deviceName,
                                           hostId));
        ps.setString(4, bindingId.toString()); //resource id
        ps.setString(5, UUID.randomUUID().toString()); // transaction id
        ps.setDate(6, new java.sql.Date(new Date().getTime()));
        ps.setString(7, hostId.toString()); //tenant id
        ps.executeUpdate();
    }

    private void insertPortBinding(Connection connect, UUID id, UUID portId,
                                   String interfaceName, UUID hostId)
            throws SQLException {
        PreparedStatement ps = connect.prepareStatement(INSERT_PORT_BINDING);
        ps.setString(1, id.toString());
        ps.setString(2, portId.toString());
        ps.setString(3, hostId.toString());
        ps.setString(4, interfaceName);
        ps.executeUpdate();
    }

    private void deletePortBinding(Connection connect, UUID bindingId)
            throws SQLException {
        PreparedStatement ps = connect.prepareStatement(DELETE_PORT_BINDING);
        ps.setString(1, bindingId.toString());
        ps.executeUpdate();
    }

    private void createBindEntries(UUID portId, String deviceName,
                                   UUID hostId) {
        Connection connect = connectToDatabase();
        try {
            UUID bindingId = UUID.randomUUID();
            insertTask(connect, bindingId, TASK_TYPE.CREATE_TASK_TYPE, portId,
                       deviceName, hostId);
            insertPortBinding(connect, bindingId, portId, deviceName, hostId);
        } catch (SQLException e) {
            log.error("Failed to create port binding entries");
            failCloseConnection(connect);
            throw new RuntimeException(e);
        }
        successCloseConnection(connect);
    }

    private UUID getBindingId(Connection connect, UUID portId) {
        try {
            PreparedStatement ps = connect.prepareStatement(PORT_BINDING_QUERY);
            ps.setString(1, portId.toString());
            ResultSet rs = ps.executeQuery();
            rs.next();
            return UUID.fromString(rs.getString("id"));
        } catch (SQLException e) {
            failCloseConnection(connect);
            log.error("getBindingId failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private void removeBindEntries(UUID portId, UUID hostId) {
        Connection connect = connectToDatabase();
        try {
            UUID bindingId = getBindingId(connect, portId);
            insertTask(connect, bindingId, TASK_TYPE.DELETE_TASK_TYPE, portId,
                       null, hostId);
            deletePortBinding(connect, bindingId);
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

        try {
            dataClient.hostsDelVrnPortMapping(hostId, portId);
        } catch (StateAccessException e) {
            return MM_CTL_RET_CODE.STATE_ERROR.getResult(e);
        } catch (Exception e) {
            return MM_CTL_RET_CODE.UNKNOWN_ERROR.getResult(e);
        }

        removeBindEntries(portId, hostId);

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
                requireBinding(ConfigProvider.class);
                bind(MidolmanConfig.class).toProvider(
                        MidolmanModule.MidolmanConfigProvider.class)
                        .asEagerSingleton();
                bind(HostConfig.class)
                        .toProvider(HostConfigProvider.class)
                        .asEagerSingleton();
                install(new ZookeeperConnectionModule(
                    ZookeeperConnectionWatcher.class
                ));
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
                    injector.getInstance(HostConfig.class),
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
