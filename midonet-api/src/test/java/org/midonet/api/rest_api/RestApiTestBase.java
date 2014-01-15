/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.rest_api;

import com.sun.jersey.test.framework.AppDescriptor;
import com.sun.jersey.test.framework.JerseyTest;
import org.midonet.api.validation.MessageProperty;
import org.midonet.client.dto.DtoError;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.UUID;

import static org.junit.Assert.assertEquals;

public abstract class RestApiTestBase extends JerseyTest {

    public RestApiTestBase(AppDescriptor desc) {
        super(desc);
    }

    protected void assertErrorMatches(
            DtoError actual, String expectedTemplateCode, Object... args) {
        String expectedMsg = MessageProperty.getMessage(expectedTemplateCode, args);
        String actualMsg = (actual.getViolations().isEmpty()) ?
                actual.getMessage() :
                actual.getViolations().get(0).get("message");
        assertEquals(expectedMsg, actualMsg);
    }

    protected URI addIdToUri(URI base, UUID id) throws URISyntaxException {
        return new URI(base.toString() + "/" + id.toString());
    }
}
