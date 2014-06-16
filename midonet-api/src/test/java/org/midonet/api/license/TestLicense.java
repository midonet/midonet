/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.license;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import javax.ws.rs.core.MediaType;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.GenericType;
import com.sun.jersey.test.framework.JerseyTest;
import net.java.truelicense.core.io.Source;
import org.apache.commons.io.IOUtils;
import org.junit.Before;
import org.junit.Test;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.rest_api.DtoWebResource;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.client.dto.DtoApplication;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

public class TestLicense extends JerseyTest {
    private static final int STATUS_OK = 200;
    private static final int STATUS_NO_CONTENT = 204;
    private static final int STATUS_BAD_REQUEST = 400;
    private static final int STATUS_NOT_FOUND = 404;
    private static final int STATUS_CONFLICT = 409;

    private static final String fileValidLicense1 = "license-valid-1.4-1.lic";
    private static final String fileValidLicense2 = "license-valid-1.4-2.lic";
    private static final String fileExpiredLicense = "license-expired-1.4.lic";

    private static final String zeroId = new UUID(0L, 0L).toString();
    private static final String invalidId = "invalid";

    private DtoWebResource resource;
    private DtoApplication app;

    public TestLicense() {
        super(FuncTest.appDesc);
    }

    @Before
    public void setUp() throws Exception {
        super.setUp();
        resource = new DtoWebResource(resource());
        app = resource.getWebResource().path("/")
                      .accept(MediaType.APPLICATION_JSON)
                      .get(DtoApplication.class);
    }

    @Test
    public void testListNoLicenses() {
        List<License> licenses = listLicenses();

        assertNotNull(licenses);
        assertTrue(licenses.isEmpty());
    }

    @Test
    public void testInstallValidLicense() throws Exception {
        Source source = loadFromResource(fileValidLicense1);

        List<License> licenses = listLicenses();

        assertNotNull(licenses);
        assertTrue(licenses.isEmpty());

        License licenseInstall = installLicense(source, STATUS_OK);

        assertNotNull(licenseInstall);
        assertTrue(licenseInstall.isValid());

        licenses = listLicenses();

        assertNotNull(licenses);
        assertEquals(licenses.size(), 1);

        License licenseDelete = deleteLicense(licenseInstall.getId(),
                                              STATUS_OK);

        assertNotNull(licenseDelete);
        assertEquals(licenseInstall, licenseDelete);

        licenses = listLicenses();

        assertNotNull(licenses);
        assertTrue(licenses.isEmpty());
    }

    @Test
    public void testInstallInvalidLicense() throws Exception {
        Source source = loadFromResource(fileExpiredLicense);

        List<License> licenses = listLicenses();

        assertNotNull(licenses);
        assertTrue(licenses.isEmpty());

        License license = installLicense(source, STATUS_BAD_REQUEST);
    }

    @Test
    public void testInstallMultipleLicenses() throws Exception {
        Source source1 = loadFromResource(fileValidLicense1);
        Source source2 = loadFromResource(fileValidLicense2);

        List<License> licenses = listLicenses();

        assertNotNull(licenses);
        assertTrue(licenses.isEmpty());

        License licenseInstall1 = installLicense(source1, STATUS_OK);

        assertNotNull(licenseInstall1);
        assertTrue(licenseInstall1.isValid());

        licenses = listLicenses();

        assertNotNull(licenses);
        assertEquals(licenses.size(), 1);

        License licenseInstall2 = installLicense(source2, STATUS_OK);

        assertNotNull(licenseInstall2);
        assertTrue(licenseInstall2.isValid());

        licenses = listLicenses();

        assertNotNull(licenses);
        assertEquals(licenses.size(), 2);

        License licenseDelete = deleteLicense(licenseInstall1.getId(),
                                              STATUS_OK);

        assertNotNull(licenseDelete);
        assertEquals(licenseInstall1, licenseDelete);

        licenseDelete = deleteLicense(licenseInstall2.getId(), STATUS_OK);

        assertNotNull(licenseDelete);
        assertEquals(licenseInstall2, licenseDelete);

        licenses = listLicenses();

        assertNotNull(licenses);
        assertTrue(licenses.isEmpty());
    }

    @Test
    public void testInstallDuplicateLicense() throws Exception {
        Source source = loadFromResource(fileValidLicense1);

        List<License> licenses = listLicenses();

        assertNotNull(licenses);
        assertTrue(licenses.isEmpty());

        License licenseInstall1 = installLicense(source, STATUS_OK);

        assertNotNull(licenseInstall1);
        assertTrue(licenseInstall1.isValid());

        licenses = listLicenses();

        assertNotNull(licenses);
        assertEquals(licenses.size(), 1);

        License licenseInstall2 = installLicense(source, STATUS_CONFLICT);

        assertNotNull(licenseInstall2);
        assertNull(licenseInstall2.getId());

        licenses = listLicenses();

        assertNotNull(licenses);
        assertEquals(licenses.size(), 1);

        License licenseDelete = deleteLicense(licenseInstall1.getId(),
                                              STATUS_OK);

        assertNotNull(licenseDelete);
        assertEquals(licenseInstall1, licenseDelete);

        licenses = listLicenses();

        assertNotNull(licenses);
        assertTrue(licenses.isEmpty());
    }

    @Test
    public void testViewLicense() throws Exception {
        Source source = loadFromResource(fileValidLicense1);

        License licenseInstall = installLicense(source, STATUS_OK);

        License license = viewLicense(licenseInstall.getId(), STATUS_OK);

        assertNotNull(license);
        assertEquals(licenseInstall, license);

        license = deleteLicense(licenseInstall.getId(), STATUS_OK);

        assertNotNull(license);
        assertEquals(license, licenseInstall);
    }

    @Test
    public void testViewInvalidLicense() throws Exception {
        viewLicense(invalidId, STATUS_BAD_REQUEST);
    }

    @Test
    public void testViewNonExistingLicense() throws Exception {
        viewLicense(zeroId, STATUS_NOT_FOUND);
    }

    @Test
    public void testDeleteInvalidLicense() throws Exception {
        deleteLicense(invalidId, STATUS_BAD_REQUEST);
    }

    @Test
    public void testDeleteNonExistingLicense() throws Exception {
        deleteLicense(zeroId, STATUS_NO_CONTENT);
    }

    @Test
    public void testLicenseStatus() throws Exception {
        LicenseStatus status = licenseStatus(STATUS_OK);

        assertNotNull(status);
        assertEquals(status.getValid(), false);

        Source source = loadFromResource(fileValidLicense1);

        License licenseInstall = installLicense(source, STATUS_OK);

        assertNotNull(licenseInstall);

        status = licenseStatus(STATUS_OK);

        assertNotNull(status);
        assertEquals(status.getValid(), true);

        License licenseDelete = deleteLicense(licenseInstall.getId(),
                                              STATUS_OK);

        assertNotNull(licenseDelete);
        assertEquals(licenseInstall, licenseDelete);

        status = licenseStatus(STATUS_OK);

        assertNotNull(status);
        assertEquals(status.getValid(), false);
    }

    /**
     * Lists the current licenses.
     * @return The list of installed licenses.
     */
    private List<License> listLicenses() {
        ClientResponse response =
            resource.getAndVerifyStatus(
                ResourceUriBuilder.getLicenses(app.getUri()),
                VendorMediaType.APPLICATION_LICENSE_COLLECTION_JSON_V1,
                STATUS_OK);
        return response.getEntity(new GenericType<List<License>>() { });
    }

    /**
     * Installs a license from the specified sources and checks the response
     * status code.
     * @param source The license source.
     * @param status The status to check.
     * @return A license installation object with the installed license.
     * @throws IOException An exception thrown when the license could not be
     * read from the specified source.
     */
    private License installLicense(Source source, int status)
        throws IOException {
        ClientResponse response =
            resource.postAndVerifyStatus(
                ResourceUriBuilder.getLicenses(app.getUri()),
                MediaType.APPLICATION_OCTET_STREAM,
                IOUtils.toByteArray(source.input()), status);
        return response.getEntity(License.class);
    }

    /**
     * Deletes the license with the specified identifier and checks the response
     * status code.
     * @param id The license identifier.
     * @param status The status to check.
     */
    private License deleteLicense(String id, int status) {
        URI uri = ResourceUriBuilder.getLicense(app.getUri(), id);
        ClientResponse response =
            resource.deleteAndVerifyStatus(
                uri, VendorMediaType.APPLICATION_LICENSE_JSON_V1, status);
        return response.getStatus() == STATUS_OK ?
            response.getEntity(License.class) : null;
    }

    /**
     * Views the license with the specified identifier and checks the response
     * status code.
     * @param id The license identifier.
     * @param status The status to check.
     * @return The license object.
     */
    private License viewLicense(String id, int status) {
        URI uri = ResourceUriBuilder.getLicense(app.getUri(), id);
        ClientResponse response =
            resource.getAndVerifyStatus(
                uri, VendorMediaType.APPLICATION_LICENSE_JSON_V1, status);
        return response.getEntity(License.class);
    }

    /**
     * Returns the current license status and checks the response status code.
     * @param status The status to check.
     * @return The license status.
     */
    private LicenseStatus licenseStatus(int status) {
        URI uri = ResourceUriBuilder.getLicenseStatus(app.getUri());
        ClientResponse response =
            resource.getAndVerifyStatus(
                uri, VendorMediaType.APPLICATION_LICENSE_STATUS_JSON_V1, status);
        return response.getEntity(LicenseStatus.class);
    }

    private static Source loadFromResource(final String name) {
        return new Source() {
            @Override
            public InputStream input() throws IOException {
                return ClassLoader.getSystemResourceAsStream(name);
            }
        };
    }
}
