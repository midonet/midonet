/*
 * Copyright (c) 2012. Midokura Japan K.K.
 */

package org.midonet.client.exception;

import com.sun.jersey.api.client.ClientResponse;

import org.midonet.client.dto.DtoError;

/**
 * Author: Tomoe Sugihara <tomoe@midokura.com>
 * Date: 8/14/12
 * Time: 2:22 PM
 */
public class HttpException extends RuntimeException {

    static final long serialVersionUID = 1L;

    ClientResponse response;

    public HttpException() {
        super();
    }

    public HttpException(ClientResponse response) {
        super(response.toString());
        this.response = response;
    }

    public ClientResponse getResponse() {
        return response;
    }

    public DtoError getError() {
        return response.getEntity(DtoError.class);
    }
}
