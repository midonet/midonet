#
# Virtual Topology Accessors
#

from midonetclient.api import MidonetApi


class MidoNetApi:
    """
    Data accessor class through MidoNet API

    The methods below return DTO (python dict) or a list of DTOs
    because that should be easier (though nasty) to support
    ZK dump data, as opposed to having to construct DTO wrapper
    python objects in the ZK dao, which API client usually returns.
    We really need to refactor and DRY the schema.
    """

    def __init__(self, url='http://localhost:8080/midonet-api/',
                 username='admin', password='passw0rd', tenant_id='*'):
        self.mido_api = MidonetApi(url, username, password, tenant_id)

    def get_tenants(self):
        """Returns a list of ID of the tenants"""
        return map(lambda x: x.dto, self.mido_api.get_tenants())

    def get_chains_by_tenant_id(self, tenant_id):
        """Returns a list of chains by tenant_id"""
        return map(lambda x: x.dto,
                   self.mido_api.get_chains({'tenant_id': tenant_id}))

    def get_rules_by_chain_id(self, chain_id):
        return map(lambda x: x.dto,
                   self.mido_api.get_chain(chain_id).get_rules())

    def get_routers_by_tenant_id(self, tenant_id):
        return map(lambda x: x.dto,
                   self.mido_api.get_routers({'tenant_id': tenant_id}))
    def get_router_by_id(self, router_id):
        return self.mido_api.get_router(router_id).dto

    def get_routes_by_router_id(self, router_id):
        return map(lambda x: x.dto,
                   self.mido_api.get_router(router_id).get_routes())

    def get_ports_by_router_id(self, router_id):
        return map(lambda x: x.dto,
                   self.mido_api.get_router(router_id).get_ports())

    def get_port_by_id(self, port_id):
        return self.mido_api.get_port(port_id).dto

    def get_bridges_by_tenant_id(self, tenant_id):
        return map(lambda x: x.dto,
                   self.mido_api.get_bridges({'tenant_id': tenant_id}))
    def get_bridge_by_id(self, bridge_id):
        return self.mido_api.get_bridge(bridge_id).dto

    def get_ports_by_bridge_id(self, bridge_id):
        return map(lambda x: x.dto,
                   self.mido_api.get_bridge(bridge_id).get_ports())

    def get_hosts(self):
        return map(lambda x: x.dto,
                   self.mido_api.get_hosts())

    def get_ports_by_host_id(self, host_id):
        return map(lambda x: x.dto,
                   self.mido_api.get_host(host_id).get_ports())

    def get_bgps(self, port_id):
        return map(lambda x: x.dto,
                   self.mido_api.get_port(port_id).get_bgps())

    def get_adroutes_by_bgp_id(self, bgp_id):
        return map(lambda x: x.dto,
                   self.mido_api.get_bgp(bgp_id).get_ad_routes())


class ZooKeeperDump:
    def __init__(self):
        raise NotImplementedError()

