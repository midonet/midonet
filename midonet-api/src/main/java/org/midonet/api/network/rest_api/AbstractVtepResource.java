/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.api.network.rest_api;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import javax.validation.Validator;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;

import org.midonet.api.rest_api.AbstractResource;
import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.southbound.vtep.VtepClusterClient;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.rest_api.models.VTEPBinding;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;

import static org.midonet.cluster.rest_api.validation.MessageProperty.VTEP_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

abstract public class AbstractVtepResource extends AbstractResource {

    protected final VtepClusterClient vtepClient;
    protected final ResourceFactory factory;

    @Inject
    public AbstractVtepResource(RestApiConfig config, UriInfo uriInfo,
                               SecurityContext context, Validator validator,
                               DataClient dataClient, ResourceFactory factory,
                               VtepClusterClient vtepClient) {
        super(config, uriInfo, context, dataClient, validator);
        this.vtepClient = vtepClient;
        this.factory = factory;
    }

    protected final List<VTEPBinding> listVtepBindings(String ipAddrStr,
                                                       UUID bridgeId)
            throws SerializationException, StateAccessException {

        List<VTEPBinding> bindings;
        try {
            bindings = vtepClient.listVtepBindings(parseIPv4Addr(ipAddrStr),
                                                   bridgeId);
        } catch (NoStatePathException ex) {
            throw new NotFoundHttpException(getMessage(VTEP_NOT_FOUND,
                                                       ipAddrStr));
        }

        URI baseUri = getBaseUri();
        for (VTEPBinding binding : bindings) {
            binding.setBaseUri(baseUri);
        }
        return bindings;
    }


}
