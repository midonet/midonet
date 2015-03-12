/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.conf;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    static private String DEPRECATED_HOST_ID_FILE = "/etc/midolman/host_uuid.properties";
    static public String HOST_ID_FILE = "/etc/midonet_host_id.properties";

    private static String getHostIdFilePath() {
        String path = null;
        try {
            path = System.getProperty("midonet.host_id_filepath");
        } catch (Exception e) {
        }

        return path != null ? path : HOST_ID_FILE;
    }

    public static void useTemporaryHostId() {
        try {
            final Path path = Files.createTempFile("midonet-unit-tests", "uuid");
            System.setProperty("midonet.host_id_filepath", path.toString());
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    try {
                        Files.delete(path);
                    } catch (Throwable t) {}
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

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
     * @return ID generated or read
     *
     * @throws PropertiesFileNotWritableException
     *                                     If the properties file cannot
     *                                     be written
     */
    public static UUID getHostId() throws PropertiesFileNotWritableException {
        return getHostId(getHostIdFilePath());
    }

    /**
     * For testing purposes, package accessible.
     */
    static UUID getHostId(String path)
            throws PropertiesFileNotWritableException {

        UUID id = getIdFromPropertiesFile(path);
        if (id == null)
            id = getIdFromPropertiesFile(DEPRECATED_HOST_ID_FILE);

        if (null != id) {
            log.debug("Found host id in the local properties file: {}", id);
            return id;
        }

        id = UUID.randomUUID();
        log.debug("Generated new Host id {}", id);
        writeId(id, path);

        return id;
    }

    private static UUID getIdFromPropertiesFile(String path) {
        Properties properties = new Properties();
        try {
            properties.load(new FileInputStream(path));
            return tryParse(properties.getProperty(uuidPropertyName));
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Get the ID from the local properties file.  It throws an IOException
     * if the ID is not found in the file
     */
    public static UUID getIdFromPropertiesFile() {
        UUID id = getIdFromPropertiesFile(HOST_ID_FILE);
        if (id == null)
            id = getIdFromPropertiesFile(DEPRECATED_HOST_ID_FILE);
        return id;
    }

    /**
     * Write the ID in the properties file
     *
     * @throws PropertiesFileNotWritableException
     *          If the properties file cannot be written
     */
    private static void writeId(UUID id, String path)
        throws PropertiesFileNotWritableException {
        Properties properties = new Properties();
        properties.setProperty(uuidPropertyName, id.toString());
        File localPropertiesFile = new File(path);
        try {
            if (!localPropertiesFile.exists())
                localPropertiesFile.createNewFile();
            // If the file exists we assume that no id has been written since
            // we checked before
            properties.store(new FileOutputStream(path), null);
            log.debug("Wrote host id {} to {}", id, path);
        } catch (IOException e) {
            throw new PropertiesFileNotWritableException(
                "Properties file: " + localPropertiesFile.getAbsolutePath());
        }
    }

    private static UUID tryParse(String id) {
        return id == null || id.isEmpty() ? null : UUID.fromString(id);
    }
}
