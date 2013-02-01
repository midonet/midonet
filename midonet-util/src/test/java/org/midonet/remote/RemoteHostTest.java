/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.remote;

import org.testng.annotations.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 4/30/12
 */
public class RemoteHostTest {

    @Test
    public void testGetSpecificationFull() throws Exception {

        RemoteHost host = new TestableRemoteHost("user:pass@host:23");

        assertThat("The host should parse the spec correctly",
                   host.isValid());

        assertThat("The user name should be correct",
                   host.getUserName(), equalTo("user"));

        assertThat("The user pass should parse properly",
                   host.getUserPass(), equalTo("pass"));

        assertThat("The host name should parse properly",
                   host.getHostName(), equalTo("host"));

        assertThat("The host port should parse properly",
                   host.getHostPort(), equalTo(23));
    }

    @Test
    public void testGetSpecificationFullComplexPass() throws Exception {

        RemoteHost host = new TestableRemoteHost("user:pa@ss@host:23");

        assertThat("The host should parse the spec correctly",
                   host.isValid());

        assertThat("The user name should be correct",
                   host.getUserName(), equalTo("user"));

        assertThat("The user pass should parse properly",
                   host.getUserPass(), equalTo("pa@ss"));

        assertThat("The host name should parse properly",
                   host.getHostName(), equalTo("host"));

        assertThat("The host port should parse properly",
                   host.getHostPort(), equalTo(23));
    }

    @Test
    public void testGetSpecificationNoPort() throws Exception {

        RemoteHost host = new TestableRemoteHost("user:pass@host");

        assertThat("The host should parse the spec correctly",
                   host.isValid());

        assertThat("The user name should be correct",
                   host.getUserName(), equalTo("user"));

        assertThat("The user pass should parse properly",
                   host.getUserPass(), equalTo("pass"));

        assertThat("The host name should parse properly",
                   host.getHostName(), equalTo("host"));

        assertThat("The host port should parse properly",
                   host.getHostPort(), equalTo(22));
    }

    @Test
    public void testGetSpecificationNoPortComplexPass() throws Exception {

        RemoteHost host = new TestableRemoteHost("user:pa@ss@host");

        assertThat("The host should parse the spec correctly",
                   host.isValid());

        assertThat("The user name should be correct",
                   host.getUserName(), equalTo("user"));

        assertThat("The user pass should parse properly",
                   host.getUserPass(), equalTo("pa@ss"));

        assertThat("The host name should parse properly",
                   host.getHostName(), equalTo("host"));

        assertThat("The host port should parse properly",
                   host.getHostPort(), equalTo(22));
    }

    @Test
    public void testGetSpecificationNoPass() throws Exception {

        RemoteHost host = new TestableRemoteHost("user@host:35");

        assertThat("The host should parse the spec correctly",
                   host.isValid());

        assertThat("The user name should be correct",
                   host.getUserName(), equalTo("user"));

        assertThat("The user pass should parse properly",
                   host.getUserPass(), nullValue());

        assertThat("The host name should parse properly",
                   host.getHostName(), equalTo("host"));

        assertThat("The host port should parse properly",
                   host.getHostPort(), equalTo(35));
    }

    @Test
    public void testGetSpecificationNoPassNoPort() throws Exception {

        RemoteHost host = new TestableRemoteHost("user@host");

        assertThat("The host should parse the spec correctly",
                   host.isValid());

        assertThat("The user name should be correct",
                   host.getUserName(), equalTo("user"));

        assertThat("The user pass should parse properly",
                   host.getUserPass(), nullValue());

        assertThat("The host name should parse properly",
                   host.getHostName(), equalTo("host"));

        assertThat("The host port should parse properly",
                   host.getHostPort(), equalTo(22));
    }

    @Test
    public void testIsValid() throws Exception {
        assertThat(
            "Empty specification should be invalid",
            new TestableRemoteHost("").isValid(), is(false));
        assertThat(
            "Null specification should be invalid",
            new TestableRemoteHost(null).isValid(), is(false));
        assertThat(
            "only host specification should be valid",
            new TestableRemoteHost("hostname").isValid(), is(false));
        assertThat(
            "user:pass@host should be valid",
            new TestableRemoteHost("user:aasdfas@host").isValid(), is(true));
        assertThat(
            "user:pass:x@host should be valid",
            new TestableRemoteHost("user:aasdfas:x@host").isValid(), is(true));
        assertThat(
            "user@host:23 should be valid",
            new TestableRemoteHost("user@host:23").isValid(), is(true));
        assertThat(
            "user:pass@host:23 should be valid",
            new TestableRemoteHost("user:pass@host:23").isValid(), is(true));
        assertThat(
            "user:pa@ss@host:23 should be false (can't have @ inside a pass yet).",
            new TestableRemoteHost("user:pa@ss@host:23").isValid(), is(true));
    }

    @Test
    public void testGetSafeName() throws Exception {
        assertThat(new TestableRemoteHost("user@host").getSafeName(),
                   equalTo("user@host:22"));
        assertThat(new TestableRemoteHost("user:pass@host").getSafeName(),
                   equalTo("user@host:22"));
        assertThat(new TestableRemoteHost("user@host:23").getSafeName(),
                   equalTo("user@host:23"));
        assertThat(new TestableRemoteHost("user:pass@host").getSafeName(),
                   equalTo("user@host:22"));
    }

    class TestableRemoteHost extends RemoteHost {
        TestableRemoteHost(String specification) {
            parseSpecification(specification);
        }
    }
}
