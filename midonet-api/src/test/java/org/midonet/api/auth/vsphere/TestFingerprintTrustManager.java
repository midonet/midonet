/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.auth.vsphere;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TestFingerprintTrustManager {

    private FingerprintTrustManager trustManager;

    @Mock
    X509Certificate mockX509Certificate;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        trustManager =
                new FingerprintTrustManager(MockCertificate.SHA1_FINGERPRINT);
    }

    @Test
    public void checkServerTrusted() throws CertificateException {

        when(mockX509Certificate.getEncoded()).thenReturn(
                MockCertificate.CERTIFICATE);

        X509Certificate [] x509Certificates = new X509Certificate[] {
                mockX509Certificate
        };

        trustManager.checkServerTrusted(x509Certificates, "test");
    }

    @Test(expected=CertificateException.class)
    public void checkServerTrustedNoTrust() throws CertificateException {
        X509Certificate mockCertificate = mock(X509Certificate.class);
        when(mockCertificate.getEncoded()).thenReturn(
                new byte [] {0,0,0,0} );

        X509Certificate [] x509Certificates = new X509Certificate[] {
                mockCertificate
        };

        trustManager.checkServerTrusted(x509Certificates, "test");
    }

    @Test(expected=IllegalArgumentException.class)
    public void testIllegalArgumentEmptyChain() throws CertificateException {
        trustManager.checkServerTrusted(new X509Certificate[] {}, "test");
    }

    @Test(expected=IllegalArgumentException.class)
    public void testIllegalArgumentNullChain() throws CertificateException {
        trustManager.checkServerTrusted(null, "test");
    }

    @Test(expected=IllegalArgumentException.class)
    public void testIllegalArgumentZeroLenghtAuthType() throws CertificateException {
        trustManager.checkServerTrusted(new X509Certificate[] {
                mockX509Certificate
        }, "");
    }

    @Test(expected=IllegalArgumentException.class)
    public void testIllegalArgumentNullAuthType() throws CertificateException {
        trustManager.checkServerTrusted(new X509Certificate[] {
                mockX509Certificate
        }, null);
    }
}
