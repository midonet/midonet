# Copyright (c) 2013-2014 Midokura Europe SARL, All Rights Reserved.

from mdts.lib.bridge import Bridge
from mdts.lib.resource_base import ResourceBase
from mdts.lib.router_port import RouterPort
from mdts.lib.route import Route

# A list of
#   - filter id attribute name in Router DTO, and
#   - data field for the corresponding filter

_FILTER_SETTERS = [
    ('inbound_filter_id', '_inbound_filter'),
    ('outbound_filter_id', '_outbound_filter')
]


class Router(ResourceBase):

    def __init__(self, api, context, data):
        """
        @type api midonetclient.api.MidonetApi
        @type context mdts.lib.virtual_topology_manager.VirtualTopologyManager
        """
        super(Router, self).__init__(api, context, data)
        self._ports = {}
        self._routes = {}
        self._routers = {}
        self._bridges = {}
        self._inbound_filter = None
        self._outbound_filter = None

    def build(self):
        tenant_id = self._get_tenant_id()
        self._mn_resource = self._api.add_router()
        self._mn_resource.tenant_id(tenant_id)
        self._mn_resource.name(self._get_name())

        if 'load_balancer' in self._data:
            self._load_balancer =\
                self._context.get_load_balancer(self._data['load_balancer'])
            self._mn_resource.load_balancer_id(self._load_balancer._mn_resource.get_id())

        # Take filter names specified in the yaml file, look up corresponding
        # chain data via Virtual Topology Manager, and set their chain IDs to
        # Router DTO. Raise an exception if no corresponding chain is found.
        # TODO(tomohiko) Also updates _inbound_filter and _outbound_filter
        for filter_field in ['inbound_filter_id','outbound_filter_id']:
            if filter_field in self._data:
                self._context.look_up_resource(
                        self._mn_resource, filter_field, self._data[filter_field])

        self._mn_resource.create()

        if 'load_balancer' in self._data and self._load_balancer:
            self._load_balancer.router(self)

        for port in self._data.get('ports') or []:
            self.add_port(port['port'])

        for route in self._data.get('routes') or []:
            self.add_route(route['route'])

        for router in self._data.get('routers') or []:
            self.add_router(router['router'])

        for bridge in self._data.get('bridges') or []:
            self.add_bridge(bridge['bridge'])

    def update(self):
        """ Dynamically updates in/out-bound filters assigned to the router.

        This updates the router MN resource with a filter ID if one has been set
        in the input yaml data or programmatically done in the functional test
        script to the 'wrapper_field' attribute of the mdts.lib.router.Router
        object.
        """
        for (router_field, wrapper_field) in _FILTER_SETTERS:
            if getattr(self, wrapper_field):
                getattr(self._mn_resource, router_field)(
                        getattr(self, wrapper_field)._mn_resource.get_id())
            else:
                getattr(self._mn_resource, router_field)(None)
        self._mn_resource.update()

    def destroy(self):
        """Destroy the router including resources below in the hierarchy"""
        self.clear_routes()
        self.clear_ports()
        self.clear_routers()
        self.clear_bridges()
        self._mn_resource.delete()

    """
    port helper functions
    """
    def clear_ports(self):
        """delete all ports on this router"""
        for key in self._ports:
            self._ports[key].destroy()
        self._ports = {}

    def get_port(self, port_id):
        return self._ports[port_id]

    def add_port(self, port):
        """create and add port from dictionary data"""
        port_obj = RouterPort(self._api, self._context, self, port)
        port_obj.build()
        self._ports[port['id']] = port_obj

    """
    route helper functions
    """
    def clear_routes(self):
        """remove and destroy the routes on a router"""
        for key in self._routes:
            self._routes[key].destroy()
        self._routes = {}

    def add_route(self, route):
        """create and add a new route from dictionary data"""
        route_obj = Route(self._api, self._context, self, route)
        route_obj.build()
        self._routes[route['id']] = route_obj

    def set_routes(self, routes):
        """remove all existing routes, then add a new set from existing data"""
        self.clear_routes()
        for key in routes:
            self.add_route(routes[key])

    """
    router helper functions
    """
    def clear_routers(self):
        """remove and destroy the all routers below this router"""
        for key in self._routers:
            self._routers[key].destroy()
        self._routers = {}

    def add_router(self, router):
        """create and add a new router from dictionary data"""
        router_obj = Route(self._api, self._context, router)
        router_obj.build()
        self._routers[router['name']] = router_obj

    """
    bridge helper functions
    """
    def clear_bridges(self):
        """remove and destroy all bridges connected to the"""
        for key in self._bridges:
            self._bridges[key].destroy()
        self._bridges = {}

    def add_bridge(self, bridge):
        """create and add a new route from dictionary data"""
        bridge_obj = Bridge(self._api, self._context, bridge)
        bridge_obj.build()
        self._bridges[bridge['name']] = bridge_obj
        #TODO: connect bridge to this router

    def set_inbound_filter(self, rule_chain):
        self._inbound_filter = rule_chain
        self.update()

    def get_inbound_filter(self):
        return self._inbound_filter

    def set_outbound_filter(self, rule_chain):
        self._outbound_filter = rule_chain
        self.update()

    def get_outbound_filter(self):
        return self._outbound_filter

    """
    Load balancer helper functions
    """
    def set_load_balancer(self, load_balancer):
        """
        @type load_balancer mdts.lib.load_balancer.LoadBalancer
        """
        self._load_balancer = load_balancer
        self.update()

    def get_load_balancer(self):
        return self._load_balancer
