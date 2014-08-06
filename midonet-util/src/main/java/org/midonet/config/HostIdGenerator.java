/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HostIdGenerator will be used to generate unique ID for the controllers. These
 * identifiers will be used by the interface management.
 * The class will try to retrieve this id (ordered according to priority):
 * <ol>
 * <li>from the conf file</li>
 * <li>from a local properties file</li>
 * <li>the class will generate a random id</li>
 * </ol>
 */
public final class HostIdGenerator {
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
    public static UUID getHostId(HostIdConfig config)
        throws PropertiesFileNotWritableException {

        UUID id = getIdFromConfigFile(config);
        if (null != id) {
            log.info("Found ID in the configuration file: {}", id);
            return id;
        }

        log.debug("No previous ID found in the local config");

        id = getIdFromPropertiesFile(config.getHostPropertiesFilePath());
        if (null != id) {
            log.debug("Found ID in the local properties file: {}", id);
            return id;
        }

        id = UUID.randomUUID();
        log.debug("Generated new id {}", id);
        writeId(id, config.getHostPropertiesFilePath());

        return id;
    }

    /**
     * Reads a host identifier from the configuration file, the properties file.
     * If no identifier is found, the method generates a new identifier.
     *
     * The configuration file takes precedence over the properties file.
     *
     * @param config The host agent configuration instance to use for
     *               parameters.
     *
     * @return The read or generated identifier.
     */
    public static UUID readHostId(HostIdConfig config) {
        UUID id = getIdFromConfigFile(config);
        if (null != id) {
            log.info("Found ID in the configuration file: {}", id);
            return id;
        }

        log.debug("No previous ID found in the local config");

        id = getIdFromPropertiesFile(config.getHostPropertiesFilePath());
        if (null != id) {
            log.debug("Found ID in the local properties file: {}", id);
            return id;
        }

        id = UUID.randomUUID();
        log.debug("Generated new id {}", id);

        return id;
    }

    /**
     * Writes a host identifier to the properties file.
     * @param id The host identifier.
     * @param config The host agent configuration instance to use for
     *               parameters.
     */
    public static void writeHostId(UUID id, HostIdConfig config)
        throws PropertiesFileNotWritableException {
        writeId(id, config.getHostPropertiesFilePath());
    }

    /**
     * Get the ID from the config file
     *
     * @param config
     * @return
     */
    private static UUID getIdFromConfigFile(HostIdConfig config) {
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
        if (null == localPropertiesFilePath)
            return null;
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
        if (null == localPropertiesFilePath)
            return;
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
