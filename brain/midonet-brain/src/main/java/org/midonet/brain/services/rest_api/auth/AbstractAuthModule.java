/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.brain.services.rest_api.auth;

import java.net.MalformedURLException;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provides;
import com.google.inject.Singleton;

import org.midonet.brain.services.rest_api.auth.keystone.KeystoneConfig;
import org.midonet.brain.services.rest_api.auth.keystone.v2_0.KeystoneClient;
import org.midonet.brain.services.rest_api.auth.vsphere.VSphereClient;
import org.midonet.brain.services.rest_api.auth.vsphere.VSphereConfig;
import org.midonet.brain.services.rest_api.auth.vsphere.VSphereConfigurationException;
import org.midonet.config.ConfigProvider;

abstract public class AbstractAuthModule extends AbstractModule {

    @Override
    protected void configure() {
        requireBinding(ConfigProvider.class);
        bindAuthServices();
        bindAuthorizers();
    }

    abstract public void bindAuthServices();

    abstract public void bindAuthorizers();

    // -- Keystone --
    @Provides @Singleton
    @Inject
    KeystoneConfig provideKeystoneConfig(ConfigProvider provider) {
        return provider.getConfig(KeystoneConfig.class);
    }

    @Provides @Singleton
    @Inject
    KeystoneClient provideKeystoneClient(KeystoneConfig keystoneConfig) {
        return new KeystoneClient(
                keystoneConfig.getServiceHost(),
                keystoneConfig.getServicePort(),
                keystoneConfig.getServiceProtocol(),
                keystoneConfig.getAdminToken());
    }

    // -- vSphere --
    @Provides @Singleton
    @Inject
    VSphereConfig provideVSphereConfig(ConfigProvider provider) {
        return provider.getConfig(VSphereConfig.class);
    }

    @Provides
    @Inject
    VSphereClient provideVSphereClient(VSphereConfig vSphereConfig)
            throws MalformedURLException, AuthException {
        String ignoreServerCertificate =
                vSphereConfig.ignoreServerCert();

        if(ignoreServerCertificate.equalsIgnoreCase("true")) {
            return new VSphereClient(vSphereConfig.getServiceSdkUrl());
        }
        else if(ignoreServerCertificate.equalsIgnoreCase("false")) {
            return new VSphereClient(vSphereConfig.getServiceSdkUrl(),
                    vSphereConfig.getServiceSSLCertFingerprint());
        }

        throw new VSphereConfigurationException("Unrecognized option for " +
                "ignore_server_cert: " + ignoreServerCertificate);
    }

}
