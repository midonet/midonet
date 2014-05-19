/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.auth.vsphere;

import java.net.MalformedURLException;
import java.net.URL;
import java.rmi.RemoteException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import com.google.common.base.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.api.auth.AuthException;

public class VSphereClient {

    private final static Logger log =
            LoggerFactory.getLogger(VSphereClient.class);

    private final URL sdkUrl;
    private final boolean ignoreServerCertificate;

    public VSphereClient(String sdkUrl)
            throws MalformedURLException, AuthException {
        this.sdkUrl = new URL(sdkUrl);
        this.ignoreServerCertificate = true;
    }

    public VSphereClient(String sdkUrl, String trustedFingerprint)
            throws MalformedURLException, AuthException {
        this.sdkUrl = new URL(sdkUrl);
        this.ignoreServerCertificate = false;
        installFingerprintTrustManager(trustedFingerprint);
    }

    /**
     * Login to the vSphere API with the given credentials
     * @return
     *      The {@code VSphereServiceInstance} associated to the user session
     * @throws RemoteException
     *      If an exception occur while exchanging data with the remote server
     * @throws MalformedURLException
     *      If a malformed url has been passed
     */
    public VSphereServiceInstance loginWithCredentials(String username,
                                                       String password)
            throws RemoteException, MalformedURLException {
        VSphereServiceInstance vSphereServiceInstance =
                VSphereServiceInstance.forCredentials(sdkUrl, username,
                        password, ignoreServerCertificate);

        log.info(String.format("Successfully logged in %s with username: %s",
                sdkUrl, username));

        return vSphereServiceInstance;
    }

    /**
     * Login to the vSphere API using a soap session cookie
     * @param soapSessionId
     *      The session cookie (vmware_soap_session=) returned by the vSphere
     *      server
     * @return
     *      The {@code VSphereServiceInstance} associated to the user session
     * @throws RemoteException
     *      If an exception occur while exchanging data with the remote server
     * @throws MalformedURLException
     *      If a malformed url has been passed
     */
    public VSphereServiceInstance loginWithSessionCookie(String soapSessionId)
            throws RemoteException, MalformedURLException {
        VSphereServiceInstance vSphereServiceInstance =
                VSphereServiceInstance.forSessionCookie(sdkUrl, soapSessionId,
                        ignoreServerCertificate);

        log.info(String.format("Successfully logged in %s", sdkUrl));

        return vSphereServiceInstance;
    }

    public String getURL() {
        return sdkUrl.toString();
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("Url", sdkUrl)
                .add("TrustAllCertificates", ignoreServerCertificate)
                .toString();
    }

    /**
     * Trust the server certificate through its fingerprint (SHA-1 hash)
     * @param trustedFingerprint
     *    the server certificate fingerprint in the form of an hex SHA-1
     *    hash, eg:
     *    "20:4D:FB:4E:07:D8:E3:7F:67:AD:93:1A:8A:64:65:49:12:E8:50:88"
     */
    private void installFingerprintTrustManager(String trustedFingerprint)
            throws AuthException {
        TrustManager[] trustManagers = new TrustManager[] {
            new FingerprintTrustManager(trustedFingerprint)
        };

        SSLContext context;
        try {
            context = SSLContext.getInstance("SSL");
        }
        catch (NoSuchAlgorithmException e) {
            throw new VSphereAuthException(e);
        }

        context.getServerSessionContext().setSessionTimeout(0);

        try {
            context.init(null, trustManagers, null);
        }
        catch (KeyManagementException e) {
            throw new VSphereAuthException(e);
        }

        // WARN: This is going to replace how the SSL certificate validation
        // will be performed GLOBALLY. It's ok for now but this could possibly
        // affect future SSL connections to different external endpoints.
        HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
    }
}
