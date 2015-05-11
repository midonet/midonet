package org.midonet.cluster.services.rest_api.resources

import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.UriInfo
import javax.ws.rs.{GET, Path, Produces}

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.models.Application
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._

@RequestScoped
@Path("/")
class ApplicationResource @Inject()(backend: MidonetBackend,
                                    uriInfo: UriInfo,
                                    bridgeResource: BridgeResource,
                                    chainResource: ChainResource,
                                    hostResource: HostResource,
                                    loginResource: LoginResource,
                                    portResource: PortResource,
                                    routeResource: RouteResource,
                                    routerResource: RouterResource,
                                    ruleResource: RuleResource,
                                    systemStateResource: SystemStateResource,
                                    tunnelZoneResource: TunnelZoneResource)
    extends MidonetResource(backend, uriInfo) {

    @GET
    @Produces(Array(APPLICATION_JSON,
                    APPLICATION_JSON_V5))
    def application: Application = {
        log.debug(s"${getClass.getName} entered on ${uriInfo.getAbsolutePath}")
        new Application(uriInfo.getAbsolutePathBuilder.build())
    }

    @Path("bridges")
    def bridges = bridgeResource

    @Path("chains")
    def chains = chainResource

    @Path("hosts")
    def hosts = hostResource

    @Path("login")
    def login = loginResource

    @Path("ports")
    def ports = portResource

    @Path("routers")
    def routers = routerResource

    @Path("routes")
    def routes = routeResource

    @Path("rules")
    def rules = ruleResource

    @Path("system_state")
    def systemState = systemStateResource

    @Path("tunnel_zones")
    def tunnelZones = tunnelZoneResource

}
