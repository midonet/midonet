/*
 * @(#)WildCardJacksonJaxbJsonProvider        1.6 11/11/11
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.jaxrs;

import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.codehaus.jackson.jaxrs.JacksonJaxbJsonProvider;

/**
 * JacksonJaxbJsonProvider that consumes and produces wildcard media types.
 * 
 * @version 1.6 11 Nov 2011
 * @author Ryu Ishimoto
 */
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.WILDCARD)
public class WildCardJacksonJaxbJsonProvider extends JacksonJaxbJsonProvider {
}
