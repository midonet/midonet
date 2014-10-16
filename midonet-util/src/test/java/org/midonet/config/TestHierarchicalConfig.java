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

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 6/14/12
 */
public class TestHierarchicalConfig {

    ConfigProvider configProvider;
    HierarchicalConfiguration backend;

    @Before
    public void setUp() throws Exception {
        backend = new HierarchicalConfiguration();
        configProvider = ConfigProvider.providerForIniConfig(backend);
    }

    @After
    public void tearDown() throws Exception {
        backend.clear();
    }

    @ConfigGroup("group")
    public static interface BoolConfig {
        @ConfigBool(key = "booleanFalse", defaultValue = false)
        boolean getBooleanFalse();

        @ConfigBool(key = "booleanTrue", defaultValue = true)
        boolean getBooleanTrue();

        @ConfigGroup("custom")
        @ConfigBool(key = "boolean", defaultValue = true)
        boolean getBooleanCustomGroup();
    }

    @Test
    public void testBooleanConfig() throws Exception {
        BoolConfig config = configProvider.getConfig(BoolConfig.class);

        assertThat(config.getBooleanFalse(), is(false));
        backend.setProperty("group.booleanFalse", "true");
        assertThat(config.getBooleanFalse(), is(true));

        assertThat("", config.getBooleanTrue(), is(true));
        backend.setProperty("group.booleanTrue", "false");
        assertThat("", config.getBooleanTrue(), is(false));

        assertThat("key with custom group should be resolved properly",
                   config.getBooleanCustomGroup(), is(true));
        backend.setProperty("custom.boolean", "false");
        assertThat("key with custom group should be resolved properly",
                   config.getBooleanCustomGroup(), is(false));
    }

    @ConfigGroup("group")
    interface IntConfig {
        @ConfigInt(key = "int", defaultValue = 8069)
        int getInt();

        @ConfigGroup("custom")
        @ConfigInt(key = "int", defaultValue = 3001)
        int getIntCustomGroup();
    }

    @Test
    public void getIntConfig() throws Exception {
        IntConfig config = configProvider.getConfig(IntConfig.class);

        assertThat(config.getInt(), is(8069));
        backend.setProperty("group.int", "3012");
        assertThat(config.getInt(), is(3012));

        assertThat("key with custom group should be resolved properly",
                   config.getIntCustomGroup(), is(3001));
        backend.setProperty("custom.int", "3012");
        assertThat("key with custom group should be resolved properly",
                   config.getIntCustomGroup(), is(3012));
    }


    @ConfigGroup("group")
    interface LongConfig {
        @ConfigLong(key = "long", defaultValue = 12345678901l)
        long getLong();

        @ConfigGroup("custom")
        @ConfigLong(key = "long", defaultValue = 912345678901l)
        long getLongCustomGroup();
    }

    @Test
    public void getLongConfig() throws Exception {
        LongConfig config = configProvider.getConfig(LongConfig.class);

        assertThat(config.getLong(), is(12345678901l));
        backend.setProperty("group.long", "12345678905");
        assertThat(config.getLong(), is(12345678905l));

        assertThat("key with custom group should be resolved properly",
                   config.getLongCustomGroup(), is(912345678901l));
        backend.setProperty("custom.long", "612345678901");
        assertThat("key with custom group should be resolved properly",
                   config.getLongCustomGroup(), is(612345678901l));
    }

    @ConfigGroup("group")
    interface StrConfig {
        @ConfigString(key = "str", defaultValue = "midokura")
        String getString();

        @ConfigGroup("custom")
        @ConfigString(key = "str", defaultValue = "midolman")
        String getStringCustomGroup();
    }

    @Test
    public void testStringConfig() throws Exception {
        StrConfig config = configProvider.getConfig(StrConfig.class);

        assertThat(config.getString(), is("midokura"));
        backend.setProperty("group.str", "midonet");
        assertThat(config.getString(), is("midonet"));

        assertThat("key with custom group should be resolved properly",
                   config.getStringCustomGroup(), is("midolman"));
        backend.setProperty("custom.str", "midonet-forever");
        assertThat("key with custom group should be resolved properly",
                   config.getStringCustomGroup(), is("midonet-forever"));
    }

    interface IntStrConfig extends IntConfig, StrConfig {

        @Override
        @ConfigGroup("bibiri")
        @ConfigInt(key = "int2", defaultValue = 10)
        int getInt();
    }

    @Test
    public void testOverriding() throws Exception {
        IntStrConfig config = configProvider.getConfig(IntStrConfig.class);

        assertThat(config.getInt(), is(10));
        backend.setProperty("bibiri.int2", "18");
        assertThat(config.getInt(), is(18));
    }

    interface MissingAnnotations {

        String getNotAnnotatedValue();

        @ConfigBool(key = "x", defaultValue = true)
        String getAnnotatedButNoGroup();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMissingAllAnnotations() throws Exception {
        MissingAnnotations config = configProvider.getConfig(MissingAnnotations.class);

        config.getNotAnnotatedValue();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMissingGroupAnnotation() throws Exception {
        MissingAnnotations config = configProvider.getConfig(MissingAnnotations.class);
        config.getAnnotatedButNoGroup();
    }
}
