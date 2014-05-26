/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.license;

import java.util.List;
import java.util.UUID;
import javax.annotation.security.RolesAllowed;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;
import net.java.truelicense.core.LicenseManagementException;
import net.java.truelicense.core.LicenseValidationException;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.BadRequestHttpException;
import org.midonet.api.rest_api.ConflictHttpException;
import org.midonet.api.rest_api.InternalServerErrorHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.api.validation.MessageProperty;
import org.midonet.cluster.DataClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Root resource class for MidoNet licenses.
 */
@RequestScoped
public class LicenseResource extends AbstractResource {
    private final static Logger log = LoggerFactory
            .getLogger(LicenseResource.class);
    private final LicenseManager manager;

    @Inject
    public LicenseResource(RestApiConfig config, UriInfo uriInfo,
                           SecurityContext context, DataClient dataClient,
                           LicenseManager manager) {
        super(config, uriInfo, context, dataClient);
        this.manager = manager;
    }

    @POST
    @RolesAllowed({AuthRole.ADMIN})
    @Consumes({MediaType.APPLICATION_OCTET_STREAM})
    @Produces({VendorMediaType.APPLICATION_LICENSE_JSON_V1})
    public License install(byte[] data) {
        try {
            License license = manager.install(data);
            if (license != null) {
                license.setBaseUri(getBaseUri());
            }
            return license;
        } catch (final LicenseValidationException ex) {
            throw new BadRequestHttpException(ex,
                MessageProperty.getMessage(
                    MessageProperty.LICENSE_INSTALL_NOT_VALID, ex.getMessage()));
        } catch (final LicenseExistsException ex) {
            throw new ConflictHttpException(ex,
                MessageProperty.getMessage(
                    MessageProperty.LICENSE_INSTALL_ID_EXISTS, ex.getLicenseId()
                ));
        } catch (final LicenseManagementException ex) {
            throw new InternalServerErrorHttpException(ex,
                MessageProperty.getMessage(
                    MessageProperty.LICENSE_INSTALL_FAILED, ex.getMessage()));
        }
    }

    @GET
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_LICENSE_LIST_JSON_V1})
    public List<License> list() {
        try {
            List<License> licenses = manager.list();
            for (License license : licenses) {
                license.setBaseUri(getBaseUri());
            }
            return licenses;
        } catch (final LicenseManagementException ex) {
            throw new InternalServerErrorHttpException(ex,
                MessageProperty.getMessage(
                    MessageProperty.LICENSE_ACCESS_FAILED, ex.getMessage()));
        }
    }

    @GET
    @Path("{id}")
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_LICENSE_JSON_V1})
    public License view(@PathParam("id") String id) {
        try {
            License license = manager.get(UUID.fromString(id));
            if (license != null) {
                license.setBaseUri(getBaseUri());
            }
            return license;
        } catch (final IllegalArgumentException ex) {
            throw new BadRequestHttpException(ex,
                MessageProperty.getMessage(
                    MessageProperty.LICENSE_INVALID_ID_FORMAT));
        } catch (final NullPointerException ex) {
            throw new NotFoundHttpException(ex,
                MessageProperty.getMessage(MessageProperty.LICENSE_NOT_FOUND,
                                           id));
        } catch (final LicenseManagementException ex) {
            throw new InternalServerErrorHttpException(ex,
                MessageProperty.getMessage(
                    MessageProperty.LICENSE_ACCESS_FAILED, ex.getMessage()));
        }
    }

    @DELETE
    @Path("{id}")
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_LICENSE_JSON_V1})
    public License delete(@PathParam("id") String id) {
        try {
            License license = manager.delete(UUID.fromString(id));
            if (license != null) {
                license.setBaseUri(getBaseUri());
            }
            return license;
        } catch (final IllegalArgumentException ex) {
            throw new BadRequestHttpException(
                MessageProperty.getMessage(
                    MessageProperty.LICENSE_INVALID_ID_FORMAT));
        } catch (final LicenseManagementException ex) {
            throw new InternalServerErrorHttpException(
                MessageProperty.getMessage(
                    MessageProperty.LICENSE_ACCESS_FAILED, ex.getMessage()));
        }
    }

    @GET
    @Path("status")
    @RolesAllowed({AuthRole.ADMIN})
    @Produces({VendorMediaType.APPLICATION_LICENSE_STATUS_JSON_V1})
    public LicenseStatus status() {
        try {
            LicenseStatus status = manager.status();
            if (status != null) {
                status.setBaseUri(getBaseUri());
            }
            return status;
        } catch (LicenseManagementException ex) {
            throw new InternalServerErrorHttpException(
                MessageProperty.getMessage(
                    MessageProperty.LICENSE_ACCESS_FAILED, ex.getMessage()));
        }
    }
}
