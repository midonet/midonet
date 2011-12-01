package com.midokura.midonet.smoketest.mocks;

import javax.ws.rs.ext.ContextResolver;
import javax.ws.rs.ext.Provider;
import javax.xml.bind.JAXBContext;

import com.midokura.midonet.smoketest.mgmt.DtoRule;
import com.sun.jersey.api.json.JSONConfiguration;
import com.sun.jersey.api.json.JSONJAXBContext;

@Provider
public class JaxbContextResolver implements ContextResolver<JAXBContext> {

    private JAXBContext context;
    private Class<?>[] types = { DtoRule.class };

    public JaxbContextResolver() throws Exception {
        this.context = new JSONJAXBContext(JSONConfiguration.mapped()
                .arrays("inPorts", "natTargets").build(), types);
    }

    public JAXBContext getContext(Class<?> objectType) {
        if (objectType == DtoRule.class)
            return context;
        else
            return null;
    }
}