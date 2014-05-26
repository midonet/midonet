/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.license;

import javax.annotation.concurrent.Immutable;
import javax.security.auth.x500.X500Principal;

import net.java.truelicense.core.LicenseVendorManager;
import net.java.truelicense.json.V2JsonLicenseManagementContext;
import net.java.truelicense.obfuscate.Obfuscate;
import net.java.truelicense.obfuscate.ObfuscatedString;

@Immutable
public final class LicenseManager {
    static public final String PRODUCT_NAME = "MidoNet";
    static public final X500Principal ISSUER_X500 = new X500Principal(
        "CN=Midokura Licensing Authority,O=Midokura SARL,c=CH");

    public static LicenseVendorManager manager() { return Config.vendorManager; }

    private static class Config {

        @Obfuscate
        static final String SUBJECT = "MidoNet";

        @Obfuscate
        static final String PRIVATE_KEY_STORE_NAME = "license-private.ks";

        @Obfuscate
        static final String PRIVATE_KEY_ENTRY_ALIAS = "licensekey";

        static final ObfuscatedString PRIVATE_KEY_STORE_PASSWORD =
            new ObfuscatedString(new long[] { 0xcffa7cda468798a1l,
                0x6c4021d2a08c23d9l, 0x5019903185337f69l, 0x8a4da7cf48b6fee9l });

        static final ObfuscatedString PRIVATE_KEY_ENTRY_PASSWORD =
            new ObfuscatedString(new long[] { 0xeddb2e2008089851l,
                0x7218546e6c6e5821l, 0x65985bea29e17375l, 0x7607fe273809562cl });

        static final ObfuscatedString PBE_PASSWORD =
            new ObfuscatedString(new long[] { 0x9afd8d25b0cd5266l,
                0x5145d4539e04225cl, 0xe99e105fb7b636c5l, 0x56ad0a70554c71f5l });

        static final LicenseVendorManager vendorManager =
            new V2JsonLicenseManagementContext(SUBJECT)
                .vendor()
                    .manager()
                        .keyStore()
                            .loadFromResource(PRIVATE_KEY_STORE_NAME)
                            .storePassword(PRIVATE_KEY_STORE_PASSWORD)
                            .alias(PRIVATE_KEY_ENTRY_ALIAS)
                            .keyPassword(PRIVATE_KEY_ENTRY_PASSWORD)
                            .inject()
                        .pbe()
                            .password(PBE_PASSWORD)
                            .inject()
                        .build();
    }
}
