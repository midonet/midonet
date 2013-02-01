/*
 * Copyright (c) 2012. Midokura Japan K.K.
 */

package org.midonet.client.exception;

import com.sun.jersey.api.client.ClientResponse;

/**
 * User: tomoe
 * Date: 8/14/12
 * Time: 2:22 PM
 */


public class HttpNotFoundException extends HttpException {

    public HttpNotFoundException(ClientResponse response) {
        super(response);
    }
}
