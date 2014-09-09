/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.error;

import com.sun.jersey.spi.container.ContainerRequest;
import com.sun.jersey.spi.container.ContainerResponse;
import com.sun.jersey.spi.container.ContainerResponseFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExceptionFilter implements ContainerResponseFilter {
    private final static Logger log =
            LoggerFactory.getLogger(ExceptionFilter.class);

    public ContainerResponse filter(ContainerRequest request, ContainerResponse response) {
        Throwable t = response.getMappedThrowable();
        if (null != t)
            log.info("Resource method call resulted in error response:", t);
        return response;
    }
}
