/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.auth.vsphere;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.X509TrustManager;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An {@code X509TrustManager} that trust the server certificates based on
 * a configured fingerprint.
 * The security of this method depends on the security of the cryptographic
 * hash chosen, in this case SHA-1: stronger then MD5, keep the configuration
 * simple and although not the strongest (some weaknesses have been proved
 * compared to the SHA-2 family) it's reasonably strong within the context of a
 * private cloud
 */
public class FingerprintTrustManager implements X509TrustManager {

    private static final Logger log =
            LoggerFactory.getLogger(FingerprintTrustManager.class);

    private final String trustedFingerprint;

    /**
     * @param trustedFingerprint
     *        The server certificate fingerprint in the form of an hex SHA-1
     *        hash, eg:
     *        "20:4D:FB:4E:07:D8:E3:7F:67:AD:93:1A:8A:64:65:49:12:E8:50:88"
     */
    public FingerprintTrustManager(String trustedFingerprint) {
        this.trustedFingerprint = trustedFingerprint;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        Preconditions.checkArgument(chain != null && chain.length > 0);
        Preconditions.checkArgument(!StringUtils.isEmpty(authType));
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        Preconditions.checkArgument(chain != null && chain.length > 0);
        Preconditions.checkArgument(!StringUtils.isEmpty(authType));

        MessageDigest messageDigest;
        try {
            messageDigest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new CertificateException(e);
        }

        for(X509Certificate certificate: chain) {
            final byte [] rawCertificateFingerprint =
                    messageDigest.digest(certificate.getEncoded());

            final List<String> hexCertificateFingerprint =
                    new ArrayList<>();

            for(byte aByte: rawCertificateFingerprint) {
                hexCertificateFingerprint.add(String.format("%02X", aByte));
            }

            final String fullCertificateFingerprint =
                    Joiner.on(":").join(hexCertificateFingerprint);

            log.debug(String.format("Checking fingerprint %s for certificate %s",
                    fullCertificateFingerprint, certificate.getSubjectDN()));

            if(trustedFingerprint.equalsIgnoreCase(fullCertificateFingerprint)) {
                log.debug(String.format("Found a the trusted fingerprint %s " +
                                "for certificate %s", fullCertificateFingerprint,
                        certificate.getSubjectDN()));
                return;
            }
        }

        throw new CertificateException("No trusted certificate found");
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[] {};
    }
}
