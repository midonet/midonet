/**
 * Copyright 2012 Midokura Europe SARL
 * User: Rossella Sblendido <rossella@midokura.com>
 * Date: 2/6/12
 *****************************************************
 */
package org.midonet.midolman.host;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.host.config.HostConfig;

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

        private static final long serialVersionUID = 1L;

        PropertiesFileNotWritableException(String message) {
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
     *
     * @return ID generated or read
     *
     * @throws PropertiesFileNotWritableException
     *                                     If the properties file cannot
     *                                     be written
     */
    public static UUID getHostId(HostConfig config)
            throws PropertiesFileNotWritableException {

        UUID myUUID = getIdFromConfigFile(config);
        if (null != myUUID) {
            log.info("Found ID in the configuration file: {}", myUUID);
            return myUUID;
        }

        log.debug("No previous ID found in the local config");

        String localPropertiesFilePath = config.getHostPropertiesFilePath();
        myUUID = getIdFromPropertiesFile(localPropertiesFilePath);
        if (null != myUUID) {
            log.debug("Found ID in the local properties file: {}", myUUID);
            return myUUID;
        }

        myUUID = UUID.randomUUID();
        log.debug("Generated new id {}", myUUID);
        writeId(myUUID, localPropertiesFilePath);

        return myUUID;
    }

    /**
     * Get the ID from the config file
     *
     * @param config
     * @return
     */
    private static UUID getIdFromConfigFile(HostConfig config) {
        return tryParse(config.getHostId());
    }

    /**
     * Get the ID from the local properties file.  It throws an IOException
     * if the ID is not found in the file
     *
     * @param localPropertiesFilePath
     * @return
     */
    public static UUID getIdFromPropertiesFile(String localPropertiesFilePath) {
        Properties properties = new Properties();
        try {
            properties.load(new FileInputStream(localPropertiesFilePath));
            return tryParse(properties.getProperty(uuidPropertyName));
        } catch (IOException e) {
            return null;
        }
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

    private static UUID tryParse(String id) {
        return id == null || id.isEmpty() ? null : UUID.fromString(id);
    }
}
