/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.rest_api;

import com.sun.jersey.test.framework.AppDescriptor;
import com.sun.jersey.test.framework.JerseyTest;
import org.midonet.api.validation.MessageProperty;
import org.midonet.client.dto.DtoError;

import static org.junit.Assert.assertEquals;

public class RestApiTest extends JerseyTest {

    public RestApiTest(AppDescriptor desc) {
        super(desc);
    }

    protected void assertErrorMatches(
            DtoError actual, String expectedTemplate, Object... args) {
        String expectedMsg = MessageProperty.getMessage(expectedTemplate, args);
        String actualMsg = (actual.getViolations().isEmpty()) ?
                actual.getMessage() :
                actual.getViolations().get(0).get("message");
        assertEquals(expectedMsg, actualMsg);
    }
}
