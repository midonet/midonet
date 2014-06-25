/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.license;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.google.inject.Inject;
import net.java.truelicense.core.LicenseManagementException;
import net.java.truelicense.core.LicenseValidationException;
import net.java.truelicense.core.io.MemoryStore;
import org.midonet.api.validation.MessageProperty;
import org.midonet.cluster.DataClient;
import org.midonet.license.LicenseInformation;
import org.midonet.midolman.state.StateAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A class that allows the installation of multiple licenses.
 */
public final class LicenseManager {
    private final static Logger log =
        LoggerFactory.getLogger(LicenseManager.class);

    private final Map<UUID, LicenseInstance> currentLicenses;
    private final Queue<LicenseInstance> freeLicenses;

    private final SimpleDateFormat dateFormat =
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss XXX");

    private final DataClient dataClient;

    /**
     * Creates a new license manager instance.
     * @param dataClient The data client.
     */
    @Inject
    public LicenseManager(DataClient dataClient) {
        this.dataClient = dataClient;
        currentLicenses = new ConcurrentHashMap<>();
        freeLicenses = new ConcurrentLinkedQueue<>();
    }

    /**
     * The number of installed licenses.
     * @return The number of licenses.
     */
    public int count() {
        return currentLicenses.size();
    }

    /**
     * Installs a license from the specified source.
     * @param data The license binary data.
     * @return If the license was installed successfully, the method returns a
     *         license object.
     * @throws LicenseManagementException An exception, if the installation fails.
     */
    public License install(byte[] data)
        throws LicenseManagementException {
        try {
            // Install the license internally.
            LicenseInformation info = installInternal(data);

            try {
                // Add the license to the persistent store.
                dataClient.licenseCreate(info.getLicenseId(), data);
            } catch (final StateAccessException ex) {
                log.error("The license {} was installed successfully, " +
                              "but saving the license to the license store " +
                              "failed. {}",
                          new Object[] { info.getLicenseId(), ex.getMessage() });
            }

            // If the license is installed and validated.
            return new License(info.getLicense(), true);
        } catch (final Exception ex) {
            throw ex;
        }
    }

    /**
     * Lists the current installed licenses.
     * @return A list of licenses.
     */
    public List<License> list() throws LicenseManagementException {
        final List<License> list = new ArrayList<License>(currentLicenses.size());
        for (LicenseInstance instance : currentLicenses.values()) {
            list.add(new License(instance.view(), instance.isValid()));
        }
        return list;
    }

    /**
     * Gets the license information for the specified license identifier.
     * @param id The license identifier.
     * @return The license information.
     */
    public License get(UUID id) throws LicenseManagementException {
        LicenseInstance instance = currentLicenses.get(id);

        return new License(instance.view(), instance.isValid());
    }

    /**
     * Deletes the license with the specified license identifier.
     * @param id The license identifier.
     * @return The license that was deleted, or null if there is no license
     * for the specified identifier.
     */
    public License delete(UUID id) throws LicenseManagementException {
        LicenseInstance instance = currentLicenses.remove(id);

        try {
            // Delete the license from the license store.
            dataClient.licenseDelete(id);
        } catch(final StateAccessException ex) {
            log.warn("Cannot delete license {} from the data store.", id);
        }

        try {
            if (null != instance) {
                License license = get(instance);
                instance.uninstall();
                log.info("The license {} was deleted from the license manager.",
                         id);
                return license;
            } else {
                return null;
            }
        } catch (final LicenseManagementException ex) {
            log.error("The license {} could not be deleted from the license " +
                          "manager.", id);
            throw ex;
        } finally {
            if (null != instance)
                freeLicenses.add(instance);
        }
    }

    /**
     * Determines the current license status, based on the currently installed
     * licenses.
     * @return A license status object.
     * @throws LicenseManagementException An exception, if the determination of
     * the status failed.
     */
    public LicenseStatus status() throws LicenseManagementException {
        if (currentLicenses.isEmpty())
            return new LicenseStatus(false, MessageProperty.getMessage(
                MessageProperty.LICENSE_STATUS_NOT_INSTALLED));
        LicenseInformation bestLicense = null;
        for (LicenseInstance instance : currentLicenses.values()) {
            try {
                LicenseInformation info = LicenseInformation.parse(
                    instance.view());
                bestLicense = bestLicense != null ?
                    bestLicense.getAgentQuota() < info.getAgentQuota() ?
                        info : bestLicense : info;
            }
            catch (final LicenseValidationException ex) { }
            catch (final IllegalArgumentException ex) { }
        }
        return bestLicense != null ?
            new LicenseStatus(true, String.format(
                MessageProperty.getMessage(
                    MessageProperty.LICENSE_STATUS_VALID),
                dateFormat.format(bestLicense.getLicense().getNotAfter()))) :
            new LicenseStatus(false, MessageProperty.getMessage(
                MessageProperty.LICENSE_STATUS_NOT_VALID));
    }

    /**
     * Gets the license information for the specified instance.
     * @param instance The license instance.
     * @return The license information.
     */
    private License get(LicenseInstance instance)
        throws LicenseManagementException {
        return new License(instance.view(), instance.isValid());
    }

    /**
     * Loads the current licenses from the data client.
     */
    public void load() {
        try {
            Collection<UUID> licenseList = dataClient.licenseList();

            log.info("Loaded {} licenses from the license store.",
                     licenseList.size());

            for (UUID licenseId : licenseList) {
                try {
                    installInternal(dataClient.licenseSelect(licenseId));
                } catch (final StateAccessException ex) {
                    log.error("Cannot access the license {} in the data store.",
                              licenseId);
                } catch (final LicenseManagementException ex) {
                    log.warn("Cannot install the license {} from the data store",
                             licenseId);
                }
            }
        } catch (final StateAccessException ex) {
            log.error("Loading the current licenses from the data store failed. {}",
                      ex.getMessage());
        }
    }

    /**
     * Saves all current licenses to the data client.
     */
    public void save() {
        for (Map.Entry<UUID, LicenseInstance> entry : currentLicenses.entrySet()) {
            try {
                dataClient.licenseCreate(entry.getKey(),
                                         entry.getValue().getData());
                log.info("The license {} saved successfully in the data store",
                         entry.getKey());
            } catch (final StateAccessException ex) {
                log.error("Saving the license {} to the data store failed. {}",
                          entry.getKey(), ex.getMessage());
            }
        }
    }

    /**
     * Installs a license internally in the license manager, without affecting
     * the persistent data store.
     * @param data The license binary data.
     * @return If the license was installed successfully, the method returns the
     *         license information.
     * @throws LicenseManagementException An exception, if the installation fails.
     */
    private LicenseInformation installInternal(byte[] data)
        throws LicenseManagementException {

        LicenseInstance instance = freeLicenses.poll();
        if (null == instance)
            instance = new LicenseInstance();

        try {
            log.debug("Installing a MidoNet license from {} bytes of data.",
                      data);

            net.java.truelicense.core.License license = instance.install(data);

            try {

                LicenseInformation info;
                try {
                    info = LicenseInformation.parse(license);
                } catch (final IllegalArgumentException ex) {
                    throw new LicenseValidationException(
                        MessageProperty.getMessage(
                            MessageProperty.LICENSE_INSTALL_NOT_MIDONET));
                }

                if (currentLicenses.containsKey(info.getLicenseId())) {
                    throw new LicenseExistsException(
                        info.getLicenseId(),
                        MessageProperty.getMessage(
                            MessageProperty.LICENSE_INSTALL_ID_EXISTS,
                            info.getLicenseId()));
                }
                currentLicenses.put(info.getLicenseId(), instance);

                log.info("License installation with ID {} completed successfully.",
                         info.getLicenseId());

                return info;
            } catch (final LicenseManagementException ex) {
                instance.uninstall();
                log.warn("License installation was successful, but another " +
                             "error occurred. {}", ex.getMessage());
                throw ex;
            }
        } catch (final LicenseManagementException ex) {
            freeLicenses.add(instance);
            log.error("License installation failed. {}", ex.getMessage());
            throw ex;
        }
    }
}
