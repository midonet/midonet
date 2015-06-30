/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.api.rest_api;

import java.util.UUID;

import javax.ws.rs.core.SecurityContext;

import org.slf4j.Logger;

import org.midonet.cluster.DataClient;
import org.midonet.cluster.auth.AuthRole;
import org.midonet.cluster.data.AdRoute;
import org.midonet.cluster.data.BGP;
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.Chain;
import org.midonet.cluster.data.Port;
import org.midonet.cluster.data.PortGroup;
import org.midonet.cluster.data.Router;
import org.midonet.cluster.data.Rule;
import org.midonet.cluster.data.ports.BridgePort;
import org.midonet.cluster.data.ports.RouterPort;
import org.midonet.cluster.data.rules.JumpRule;
import org.midonet.cluster.rest_api.ForbiddenHttpException;
import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

import static org.slf4j.LoggerFactory.getLogger;

public final class DataClientAuthoriser extends Authoriser {

    private final static Logger log = getLogger(DataClientAuthoriser.class);
    private final DataClient dataClient;

    public DataClientAuthoriser(DataClient dc, SecurityContext context) {
        super(context);
        this.dataClient = dc;
    }

    public boolean isAdmin() {
        return (context.isUserInRole(AuthRole.ADMIN));
    }

    public <T> boolean isOwner(T tenantId) {
        return context.getUserPrincipal().getName().equals(tenantId);
    }

    public <T> boolean isAdminOrOwner(T tenantId) {
        return isAdmin() || isOwner(tenantId);
    }

    @Override
    public Router tryAuthoriseRouter(UUID id, String what)
        throws StateAccessException, SerializationException {
        Router r = dataClient.routersGet(id);
        Router.Property tenantProperty = Router.Property.tenant_id;
        if ((r != null) && !isAdminOrOwner(r.getProperty(tenantProperty))) {
            throw new ForbiddenHttpException("Not authorized to " + what);
        }
        return r;
    }

    @Override
    public Bridge tryAuthoriseBridge(UUID id, String what)
        throws StateAccessException, SerializationException {
        Bridge b = dataClient.bridgesGet(id);
        Bridge.Property tenantProperty = Bridge.Property.tenant_id;
        if ((b != null) && !isAdminOrOwner(b.getProperty(tenantProperty))) {
            throw new ForbiddenHttpException("Not authorized to " + what);
        }
        return b;
    }

    @Override
    public Port tryAuthorisePort(UUID portId, String what)
        throws StateAccessException, SerializationException {
        Port p = dataClient.portsGet(portId);
        if (p == null) {
            return null;
        }
        if (isAdmin()) {
            return p;
        }
        if (p instanceof BridgePort) {
            tryAuthoriseBridge(p.getDeviceId(), what);
        } else if (p instanceof RouterPort) {
            tryAuthoriseRouter(p.getDeviceId(), what);
        } else {
            throw new NotFoundHttpException("Port device not found");
        }
        return p;
    }

    @Override
    public PortGroup tryAuthorisePortGroup(UUID id, String what)
        throws StateAccessException, SerializationException {
        PortGroup pg = dataClient.portGroupsGet(id);
        PortGroup.Property tenantProp = PortGroup.Property.tenant_id;
        if ((pg != null) && !isAdminOrOwner(pg.getProperty(tenantProp))) {
            throw new ForbiddenHttpException("Not authorized to " + what);
        }
        return pg;
    }

    @Override
    public Chain tryAuthoriseChain(UUID id, String what)
        throws StateAccessException, SerializationException {
        Chain c = dataClient.chainsGet(id);
        Chain.Property tenantProp = Chain.Property.tenant_id;
        if ((c != null) && !isAdminOrOwner(c.getProperty(tenantProp))) {
            throw new ForbiddenHttpException("Not authorized to " + what);
        }
        return c;
    }

    @Override
    public BGP tryAuthoriseBgp(UUID id, String what)
        throws StateAccessException, SerializationException {
        org.midonet.cluster.data.BGP bgp = dataClient.bgpGet(id);
        if (bgp == null) {
            return null;
        }
        Port<?, ?> port = dataClient.portsGet(bgp.getPortId());
        Router router = dataClient.routersGet(port.getDeviceId());
        String tenantId = router.getProperty(Router.Property.tenant_id);
        if (!isAdminOrOwner(tenantId)) {
            throw new ForbiddenHttpException("Not authorized to " + what);
        }
        return bgp;
    }

    @Override
    public AdRoute tryAuthoriseAdRoute(UUID id, String what)
        throws StateAccessException, SerializationException {

        AdRoute adRoute = dataClient.adRoutesGet(id);
        if (adRoute == null) {
            return null;
        }
        if (isAdmin()) {
            return adRoute;
        }

        tryAuthoriseBgp(adRoute.getBgpId(), what);

        return adRoute;
    }

    @Override
    public Rule tryAuthoriseRule(UUID id, String what)
        throws StateAccessException, SerializationException {
        Rule rule = dataClient.rulesGet(id);
        if (rule == null) {
            return null;
        }
        if (isAdmin()) {
            return rule;
        }
        Chain chain = dataClient.chainsGet(rule.getChainId());
        String tenantId = chain.getProperty(Chain.Property.tenant_id);
        if (tenantId == null) {
            log.warn("Cannot authorize rule {} because chain {} is missing " +
                     "tenant data", rule.getId(), chain.getId());
            throw new ForbiddenHttpException("Not authorized to " + what);
        }
        if (!isOwner(tenantId)) {
            throw new ForbiddenHttpException("Not authorized to " + what);
        }

        // Check the destination jump Chain ID if it's a jump rule
        if (rule instanceof JumpRule) {
            JumpRule typedRule = (JumpRule) rule;
            UUID jumpChainId = typedRule.getJumpToChainId();

            if (jumpChainId == null) {
                return rule;
            }

            Chain targetChain = dataClient.chainsGet(jumpChainId);

            if (targetChain == null) {
                log.warn("Attempted to jump to a non-existent resource: {}",
                         typedRule.getId());
                throw new ForbiddenHttpException("Not authorized to " + what);
            }

            tenantId = targetChain.getProperty(Chain.Property.tenant_id);
            if (tenantId == null) {
                log.warn("Cannot authorize rule {} because jump target chain " +
                         "{} is missing tenant data", typedRule.getId(),
                         targetChain.getId());
                throw new ForbiddenHttpException("Not authorized to " + what);
            }

            // Make sure the target chain is owned by the same tenant
            if (!isOwner(tenantId)) {
                throw new ForbiddenHttpException("Not authorized to " + what);
            }
        }
        return rule;
    }

}
