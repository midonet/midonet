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

import javax.servlet.ServletContext;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.doReturn;

import org.midonet.config.providers.ServletContextConfigProvider;


/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 6/14/12
 */
@RunWith(MockitoJUnitRunner.class)
public class TestServletContextConfig {

    ConfigProvider configProvider;
    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private ServletContext backend;

    @Before
    public void setUp() throws Exception {
        configProvider = new ServletContextConfigProvider(backend);
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
        doReturn("true").when(backend).getInitParameter("group-booleanFalse");
        assertThat(config.getBooleanFalse(), is(true));

        assertThat("", config.getBooleanTrue(), is(true));
        doReturn("false").when(backend).getInitParameter("group-booleanTrue");
        assertThat("", config.getBooleanTrue(), is(false));

        assertThat("key with custom group should be resolved properly",
                   config.getBooleanCustomGroup(), is(true));
        doReturn("false").when(backend).getInitParameter("custom-boolean");
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
        doReturn("3012").when(backend).getInitParameter("group-int");
        assertThat(config.getInt(), is(3012));

        assertThat("key with custom group should be resolved properly",
                   config.getIntCustomGroup(), is(3001));
        doReturn("3012").when(backend).getInitParameter("custom-int");
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
        doReturn("12345678905").when(backend).getInitParameter("group-long");
        assertThat(config.getLong(), is(12345678905l));

        assertThat("key with custom group should be resolved properly",
                   config.getLongCustomGroup(), is(912345678901l));
        doReturn("612345678901").when(backend).getInitParameter("custom-long");
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
        doReturn("midonet").when(backend).getInitParameter("group-str");
        assertThat(config.getString(), is("midonet"));

        assertThat("key with custom group should be resolved properly",
                   config.getStringCustomGroup(), is("midolman"));
        doReturn("midonet-forever").when(backend).getInitParameter("custom-str");
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
        doReturn("18").when(backend).getInitParameter("bibiri-int2");
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
