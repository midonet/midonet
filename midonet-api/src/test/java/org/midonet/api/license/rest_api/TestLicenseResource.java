/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.license.rest_api;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import net.java.truelicense.core.io.Source;

import org.apache.commons.io.IOUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import org.midonet.api.license.License;
import org.midonet.api.license.LicenseManager;
import org.midonet.api.license.LicenseResource;
import org.midonet.api.license.LicenseStatus;
import org.midonet.api.rest_api.BadRequestHttpException;
import org.midonet.api.rest_api.ConflictHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.DataClient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(MockitoJUnitRunner.class)
public class TestLicenseResource {

    private static final String fileValidLicense1 = "license-valid-1.4-1.lic";
    private static final String fileValidLicense2 = "license-valid-1.4-2.lic";
    private static final String fileExpiredLicense = "license-expired-1.4.lic";

    private static final String zeroId = new UUID(0L, 0L).toString();
    private static final String invalidId = "";

    private LicenseManager manager;
    private LicenseResource resource;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private RestApiConfig config;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private UriInfo uriInfo;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private SecurityContext context;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private DataClient dataClient;

    @Before
    public void setUp() throws Exception {
        manager = new LicenseManager(dataClient);
        resource = new LicenseResource(config, uriInfo, context, dataClient,
                                       manager);
    }

    @Test
    public void testInstallValid() throws Exception {
        testList(0);
        License license = testInstall(fileValidLicense1);
        testListContains(1, license);
        testViewExists(license);
        testDelete(license);
        testListNotContains(0, license);
    }

    @Test
    public void testInstallMultipleValid() throws Exception {
        testList(0);
        License license1 = testInstall(fileValidLicense1);
        testListContains(1, license1);
        testViewExists(license1);
        License license2 = testInstall(fileValidLicense2);
        testListContains(2, license2);
        testViewExists(license2);
        testDelete(license1);
        testListContains(1, license2);
        testListNotContains(1, license1);
        testViewExists(license2);
        testDelete(license2);
        testListNotContains(0, license2);
    }

    @Test(expected = ConflictHttpException.class)
    public void testInstallDuplicate() throws Exception {
        testList(0);
        License license = testInstall(fileValidLicense1);
        testListContains(1, license);
        testViewExists(license);
        testInstall(fileValidLicense1);
    }

    @Test(expected = BadRequestHttpException.class)
    public void testInstallExpired() throws Exception {
        testInstall(fileExpiredLicense);
    }

    @Test(expected = BadRequestHttpException.class)
    public void testViewInvalidLicense() throws Exception {
        resource.view(invalidId);
    }

    @Test(expected = NotFoundHttpException.class)
    public void testViewNonExistingLicense() throws Exception {
        resource.view(zeroId);
    }

    @Test(expected = BadRequestHttpException.class)
    public void testDeleteInvalidLicense() throws Exception {
        resource.delete(invalidId);
    }

    @Test
    public void testDeleteNonExistingLicense() throws Exception {
        resource.delete(zeroId);
    }

    @Test
    public void testStatus() throws Exception {
        LicenseStatus status = resource.status();

        assertNotNull(status);
        assertFalse(status.getValid());

        License license = testInstall(fileValidLicense1);

        status = resource.status();

        assertNotNull(status);
        assertTrue(status.getValid());

        testDelete(license);

        status = resource.status();

        assertNotNull(status);
        assertFalse(status.getValid());
    }

    private static Source loadFromResource(final String name) {
        return new Source() {
            @Override
            public InputStream input() throws IOException {
                return ClassLoader.getSystemResourceAsStream(name);
            }
        };
    }

    private License testInstall(String name) throws IOException {
        Source source = loadFromResource(name);

        License license =
            resource.install(IOUtils.toByteArray(source.input()));

        assertNotNull(license);
        assertTrue(license.isValid());

        return license;
    }

    private void testDelete(License license) {
        License licenseDelete = resource.delete(license.getId());

        assertNotNull(licenseDelete);
        assertEquals(license, licenseDelete);
    }

    private void testListContains(int count, License license) {
        List<License> list = resource.list();

        assertNotNull(list);
        assertEquals(list.size(), count);
        assertTrue(list.contains(license));
    }

    private void testList(int count) {
        List<License> list = resource.list();

        assertNotNull(list);
        assertEquals(list.size(), count);
    }

    private void testListNotContains(int count, License license) {
        List<License> list = resource.list();

        assertNotNull(list);
        assertEquals(list.size(), count);
        assertFalse(list.contains(license));
    }

    private void testViewExists(License license) {
        License licenseView = resource.view(license.getId());

        assertNotNull(licenseView);
        assertEquals(license, licenseView);
    }
}
