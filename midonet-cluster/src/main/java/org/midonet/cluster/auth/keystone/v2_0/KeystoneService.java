/*
 * Copyright 2016 Midokura SARL
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
package org.midonet.cluster.auth.keystone.v2_0;

import com.google.inject.Inject;
import com.typesafe.config.Config;

/**
 * Legacy Keystone service kept for backwards compatibility.
 */
public class KeystoneService extends org.midonet.cluster.auth.keystone.KeystoneService {

    @Inject
    public KeystoneService(Config config) {
        super(config);
    }

}
