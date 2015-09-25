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

package org.midonet.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;
import java.util.UUID;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.midonet.conf.HostIdGenerator;

public class TestHostIdGenerator {

    static final String uuidPropertyName = "host_uuid";
    static final String hostId = "e3f9adc0-5175-11e1-b86c-0800200c9a67";
    File propFile;

    @After
    public void tearDown() throws Exception {
        if (propFile.exists())
            propFile.delete();
    }

    @Before
    public void setUp() throws Exception {
        propFile = new File(HostIdGenerator.useTemporaryHostId());

        Properties properties = new Properties();
        properties.setProperty(uuidPropertyName, hostId);
        properties.store(new FileOutputStream(propFile.getAbsolutePath()), null);
    }

    @Test
    public void getIdFromPropertyFile() throws Exception {
        UUID id = HostIdGenerator.getHostId();
        Assert.assertTrue(id.toString().equals(hostId));
    }

    @Test
    public void generateRandomId() throws Exception {
        // delete properties file
        boolean res = propFile.delete();
        Assert.assertTrue(res);
        UUID id = HostIdGenerator.getHostId();
        // check that the id has been written in the property file
        boolean exists = propFile.exists();
        Assert.assertTrue(exists);
        Properties properties = new Properties();
        properties.load(new FileInputStream(propFile.getAbsolutePath()));
        UUID idFromProperty = UUID.fromString(
            properties.getProperty(uuidPropertyName));
        Assert.assertTrue(id.equals(idFromProperty));
    }
}
