/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.license;

import net.java.truelicense.core.LicenseConsumerManager;
import net.java.truelicense.core.LicenseManagementException;
import net.java.truelicense.core.io.MemoryStore;
import net.java.truelicense.core.io.Store;
import net.java.truelicense.json.V2JsonLicenseManagementContext;
import net.java.truelicense.obfuscate.Obfuscate;
import net.java.truelicense.obfuscate.ObfuscatedString;

/**
 * A class that stores one MidoNet license.
 */
public class LicenseInstance {

    @Obfuscate
    private static final String SUBJECT = "MidoNet";

    @Obfuscate
    private static final String PUBLIC_KEY_STORE_NAME = "license-public.ks";

    @Obfuscate
    private static final String PUBLIC_CERT_ENTRY_ALIAS = "licensekey";

    private static final ObfuscatedString PUBLIC_KEY_STORE_PASSWORD =
        new ObfuscatedString(new long[] { 0x68eb532b055c26a5l,
            0x39351bb9e38fe655l, 0xbb814ec45612d507l, 0xf0f5dee8393a6c2el });

    private static final ObfuscatedString PBE_PASSWORD =
        new ObfuscatedString(new long[] { 0x4e09787402431444l,
            0x7baeb228a780e858l, 0x902cfb825d7a762dl, 0xed7f0418adb98d07l });

    private final LicenseConsumerManager manager;
    private final Store store;

    private byte[] data;

    public LicenseInstance() {
        store = new MemoryStore();

        manager = new V2JsonLicenseManagementContext(SUBJECT)
            .consumer().manager()
                .keyStore()
                    .loadFromResource(PUBLIC_KEY_STORE_NAME)
                    .storePassword(PUBLIC_KEY_STORE_PASSWORD)
                    .alias(PUBLIC_CERT_ENTRY_ALIAS)
                    .inject()
                .pbe()
                    .password(PBE_PASSWORD)
                    .inject()
                .storeIn(store)
            .build();
    }

    /**
     * Installs a license from the specified byte array.
     * @param data The license data.
     * @return The license object.
     */
    public net.java.truelicense.core.License install(byte[] data)
        throws LicenseManagementException {
        MemoryStore store = new MemoryStore(data.length);
        store.data(data);
        this.data = data;
        return manager.install(store);
    }

    /**
     * Uninstalls the current license from the license instance.
     */
    public void uninstall() throws LicenseManagementException {
        data = null;
        manager.uninstall();
    }

    /**
     * Views the current license from the license instance.
     * @return The license object.
     */
    public net.java.truelicense.core.License view()
        throws LicenseManagementException {
        return manager.view();
    }

    /**
     * Determines whether the current installed license is valid.
     * @return True if the license is valid, false otherwise.
     */
    public boolean isValid() {
        try {
            manager.verify();
        } catch (final LicenseManagementException ex) {
            return false;
        }
        return true;
    }

    /**
     * Gets the license data.
     * @return A byte array with the license data.
     */
    public byte[] getData() {
        return this.data;
    }
}
