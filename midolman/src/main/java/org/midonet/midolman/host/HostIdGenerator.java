/**
 * Copyright 2012 Midokura Europe SARL
 * User: Rossella Sblendido <rossella@midokura.com>
 * Date: 2/6/12
 *****************************************************
 */
package org.midonet.midolman.host;

import org.midonet.midolman.host.config.HostConfig;
import org.midonet.midolman.host.state.HostDirectory;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.UUID;

/**
 * HostIdGenerator will be used to generate unique ID for the controllers. These ids
 * will be used by the interface management.
 * The class will try to retrieve this id (ordered according to priority):
 * <ol>
 * <li>from the conf file</li>
 * <li>from a local properties file</li>
 * <li>the class will generate a random id</li>
 * </ol>
 */
public class HostIdGenerator {

    private final static Logger log =
        LoggerFactory.getLogger(HostIdGenerator.class);
    static private String uuidPropertyName = "host_uuid";

    /**
     * Custom exception PropertiesFileNotWritableException
     */
    public static class PropertiesFileNotWritableException extends Exception {
        PropertiesFileNotWritableException(String message) {
            super(message);
        }
    }

    /**
     * Custom exception HostIdAlreadyInUseException
     */
    public static class HostIdAlreadyInUseException extends Exception {
        HostIdAlreadyInUseException(String message) {
            super(message);
        }
    }

    /**
     * Assumptions:
     * <ul>
     * <li>Id in the config file takes priority over an id in the properties file.</li>
     * <li>Id is only generated if there isn't one in either file.</li>
     * <li>The Id can only be used if the properties file is writable.
     * <li>Under most normal circumstances, two nodes will not have the same Id
     * in their config/properties files. However, if it happens (e.g.
     * due to operator copying the file) then one of the nodes will continue to
     * get an exception from its calls to getHostId.</li>
     * <li>If a node crashes without closing its ZK connection, it may not be able
     * to reclaim its Id until the old ZK connection times out.</li>
     * </ul>
     *
     * @param config the host agent config instance to use for parameters
     * @param zkManager the zkManager to use for data write
     *
     * @return ID generated or read
     *
     * @throws HostIdAlreadyInUseException If the ID is already in use
     * @throws StateAccessException        If there's a problem in reading/writing
     *                                     the data. E.g. the path in Zk doesn't exist
     * @throws PropertiesFileNotWritableException
     *                                     If the properties file cannot
     *                                     be written
     * @throws InterruptedException
     */
    public static UUID getHostId(HostConfig config,
                                 HostZkManager zkManager)
            throws HostIdAlreadyInUseException, StateAccessException,
            PropertiesFileNotWritableException,
            InterruptedException, SerializationException {
        UUID myUUID;
        myUUID = getIdFromConfigFile(config, zkManager);

        // if it's empty read it from local file
        if (myUUID == null) {
            log.debug("No previous ID found in the local config");
            String localPropertiesFilePath =
                    config.getHostPropertiesFilePath();

            myUUID = getIdFromPropertiesFile(localPropertiesFilePath,
                                             zkManager);
            // check if it's null, if so generate it
            if (myUUID == null) {
                myUUID = generateUniqueId(zkManager);
                log.debug("Generated new id {}", myUUID);
                // write it in the properties file
                writeId(myUUID, localPropertiesFilePath);
                zkManager.createHost(myUUID, new HostDirectory.Metadata());
            } else {
                log.debug("Found ID in the local properties file: {}", myUUID);
            }
        } else {
            log.info("Found ID in the configuration file: {}", myUUID);
        }
        if (!zkManager.hostExists(myUUID))
            zkManager.createHost(myUUID, new HostDirectory.Metadata());
        return myUUID;
    }

    /**
     * Get the ID from the config file
     *
     * @param config
     * @param zkManager
     * @return
     * @throws HostIdAlreadyInUseException If the ID is already in use
     * @throws StateAccessException        If there's a problem in reading the data
     *                                     from ZK. E.g. the path doesn't exist
     */
    private static UUID getIdFromConfigFile(HostConfig config,
                                            HostZkManager zkManager)
        throws HostIdAlreadyInUseException, StateAccessException {
        UUID myUUID = null;
        String id = config.getHostId();
        if (!id.isEmpty()) {
            myUUID = UUID.fromString(id);
            // Check if it's unique, if not throw an exception. Conf file and
            // (local property + random generation) need to be used
            // alternatively.
            if (zkManager.isAlive(myUUID)) {
                throw new HostIdAlreadyInUseException(
                    "ID already in use: " + myUUID);
            }
        }
        return myUUID;
    }

    /**
     * Get the ID from the local properties file
     *
     * @param localPropertiesFilePath
     * @param zkManager
     * @return
     * @throws HostIdAlreadyInUseException If the ID is already in use
     * @throws StateAccessException        If there's a problem in reading the
     *                                     data from ZK. E.g. the path doesn't exist
     */
    private static UUID getIdFromPropertiesFile(String localPropertiesFilePath,
                                                HostZkManager zkManager)
        throws HostIdAlreadyInUseException, StateAccessException {
        UUID myUUID = null;
        Properties properties = new Properties();
        try {
            properties.load(new FileInputStream(localPropertiesFilePath));
        } catch (IOException e) {
            // If there's a problem in the file we can only re-create the ID
            return null;
        }
        String id = properties.getProperty(uuidPropertyName);
        if (id != null) {
            myUUID = UUID.fromString(id);
            // check if it's unique, if not throw an exception
            if (zkManager.isAlive(myUUID)) {
                throw new HostIdAlreadyInUseException(
                    "ID already in use: " + myUUID);
            }
        }
        return myUUID;
    }


    /**
     * Get the ID from the local properties file.  It throws an IOException
     * if the ID is not found in the file
     *
     * @param localPropertiesFilePath
     * @return
     * @throws IOException        There is no entry in the file
     */
    public static UUID getIdFromPropertiesFile(
            String localPropertiesFilePath) throws IOException {
        Properties properties = new Properties();
        properties.load(new FileInputStream(localPropertiesFilePath));
        return UUID.fromString(properties.getProperty(uuidPropertyName));
    }

    /**
     * Generate a unique id
     *
     * @param zkManager
     * @return
     * @throws StateAccessException If there's a problem in reading the data from
     *                              ZK. E.g. the path doesn't exist
     * @throws InterruptedException If the thread gets interrupted by another thread
     */
    private static UUID generateUniqueId(HostZkManager zkManager)
        throws StateAccessException, InterruptedException {
        UUID id = UUID.randomUUID();
        while (zkManager.hostExists(id)) {
            id = UUID.randomUUID();
            Thread.sleep(50);
        }
        return id;
    }

    /**
     * Write the ID in the properties file
     *
     * @throws PropertiesFileNotWritableException
     *          If the properties file cannot be written
     */
    private static void writeId(UUID id, String localPropertiesFilePath)
        throws PropertiesFileNotWritableException {
        Properties properties = new Properties();
        properties.setProperty(uuidPropertyName, id.toString());
        File localPropertiesFile = new File(localPropertiesFilePath);
        try {
            if (!localPropertiesFile.exists())
                localPropertiesFile.createNewFile();
            // If the file exists we assume that no id has been written since
            // we checked before
            properties.store(new FileOutputStream(localPropertiesFilePath),
                             null);
        } catch (IOException e) {
            throw new PropertiesFileNotWritableException(
                    "Properties file: " + localPropertiesFile.getAbsolutePath());
        }
    }
}
