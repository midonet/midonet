# Copyright 2014 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
Resource manager for virtual topology data.
"""

from mdts.lib.bridge import Bridge
from mdts.lib.chain import Chain
from mdts.lib.health_monitor import HealthMonitor
from mdts.lib.link import Link
from mdts.lib.load_balancer import LoadBalancer
from mdts.lib.mdts_exception import MdtsException
from mdts.lib.port_group import PortGroup
from mdts.lib.resource_reference import ResourceReference
from mdts.lib.router import Router
from mdts.lib.mirror import Mirror
from mdts.lib.qos_policy import QOSPolicy
from mdts.lib.tenants import get_or_create_tenant
from mdts.lib.topology_manager import TopologyManager
from mdts.lib.tracerequest import TraceRequest
from mdts.lib.vtep import Vtep
from mdts.tests.utils.utils import clear_virtual_topology_for_tenants

from midonetclient.api import MidonetApi

from webob.exc import HTTPBadRequest
import logging

LOG = logging.getLogger(__name__)


class ResourceNotFoundException(MdtsException):
    """Exception raised when a referred resource is not found."""
    pass


class InvalidResourceReferenceException(MdtsException):
    """Exception raised when a reference for a resource is in an invalid
    form."""
    pass


class DevicePortLinkingException(MdtsException):
    """Exception raised when linking device ports failed."""
    def __init__(self, link, original_exception=None):
        self._link = link
        self._original_exception = original_exception

    def __str__(self):
        return 'Failed to build device port link: %s %s' % (
                str(self._link), str(self._original_exception))


class VirtualTopologyManager(TopologyManager):

    def __init__(self, filename=None, data=None, midonet_api=None):
        """
        @type filename str
        @type midonet_api MidonetApi
        """
        super(VirtualTopologyManager, self).__init__(
            filename, data, midonet_api)

        self._vt = self._data['virtual_topology']
        self._resource_references = []
        self._routers = {}
        self._bridges = {}
        self._mirrors = {}
        self._bridge_router = {}
        self._chains = {}
        self._tracerequests = {}
        self._links = []

        #: :type: dict[str, HealthMonitor]
        self._health_monitors = {}

        #: :type: dict[str, LoadBalancer]
        self._load_balancers = {}

        self._port_groups = {}

        self._vteps = {}

        self._qos_policies = {}

    def build(self, binding_data=None):
        """ Generates virtual topology resources (bridges, routers, chains, etc.
        From the data loaded from the input yaml file.
        """

        self._api = self._midonet_api_host.get_midonet_api()

        for qos_policy in self._vt.get('qos_policies') or []:
            self.add_qos_policy(qos_policy['qos_policy'])

        for health_monitor in self._vt.get('health_monitors') or []:
            self.add_health_monitor(health_monitor['health_monitor'])

        for load_balancer in self._vt.get('load_balancers') or []:
            self.add_load_balancer(load_balancer['load_balancer'])

        for router in self._vt.get('routers') or []:
            self.add_router(router['router'])

        for bridge in self._vt.get('bridges') or []:
            self.add_bridge(bridge['bridge'])

        for link in self._vt.get('links') or []:
            self.add_link(link['link'])

        for port_group in self._vt.get('port_groups') or []:
            self.add_port_group(port_group['port_group'])

        # NOTE: Builds chains after router, bridge, etc. in order to avoid lazy
        # UUID resolution. The current MidoNet API does not allow an 'update'
        # operation on chain.
        for chain in self._vt.get('chains') or []:
            self.add_chain(chain['chain'])

        for tracerequest in self._vt.get('tracerequests') or []:
            self.add_tracerequest(tracerequest['tracerequest'])

        referrers = {}
        for reference in self._resource_references:
            self.resolve_resource_reference(reference)
            referrers[reference.get_referrer()] = None
        # Call update() on resources whose references have been resolved.
        for referrer in referrers: referrer.update()
        self._resource_references = []
        # Link peer ports
        for link in self._links:
            try: link.build()
            except HTTPBadRequest as e:
                raise DevicePortLinkingException(link, e)

        for mirror in self._vt.get('mirrors') or []:
            self.add_mirror(mirror['mirror'])

    def look_up_resource(self, referrer, setter, reference_spec):
        """Looks up a resource referred by referrer.

        If a resource is not found, registers a reference to the resource for
        lazy lookup later.

        Args:
            referrer: A resource referring to another resource.
            field: A setter for the field referring to the resource.
            reference_spec: A specification of the referred resource.
        """
        try:
            self.resolve_reference(referrer, setter, reference_spec)
        except:
            self.register_resource_reference(referrer, setter, reference_spec)

    def resolve_resource_reference(self, reference):
        """Resolves a resource reference and updates the referrer."""
        self.resolve_reference(reference.get_referrer(),
                               reference.get_referrer_setter(),
                               reference.get_reference_spec())

    def resolve_reference(self, referrer, setter, reference_spec):
        """Resolves a resource reference and updates the referrer.

        Args:
            referrer: A resource referring to another resource.
            field: A setter for the field referring to the resource.
            reference_spec: A specification of the referred resource.
        """
        resolved = None
        if isinstance(reference_spec, list):
            resolved = [self.resolve_uuid(x) for x in reference_spec]
        else:
            resolved = self.resolve_uuid(reference_spec)
        getattr(referrer, setter)(resolved)

    def resolve_uuid(self, spec):
        """ Resolves an UUID of a referenced resource."""
        if not isinstance(spec, dict):
            # If it's not a dictionary, consider that a raw UUID is specified.
            return spec
        if 'device_name' in spec and 'port_id' in spec:
            port = self.get_device_port(spec['device_name'],
                                        spec['port_id'])
            if port: return port._mn_resource.get_id()
            else:
                raise ResourceNotFoundException(
                        'No device port with device name, %s, port id, %d' % (
                                spec['device_name'], spec['port_id']))
        elif 'port_group_name' in spec:
            port_group = self.get_port_group(spec['port_group_name'])
            if port_group: return port_group._mn_resource.get_id()
            else:
                raise ResourceNotFoundException("No port group with name: %s" %
                        spec['port_group_name'])
        elif 'chain_name' in spec:
            chain = self.get_chain(spec['chain_name'])
            if chain: return chain._mn_resource.get_id()
            else:
                raise ResourceNotFoundException("No chain with name: %s" %
                        spec['chain_name'])
        else:
            raise InvalidResourceReferenceException(
                    "Invalid resource reference: %s" % str(spec))

    def destroy(self):
        """ Cleans up the virtual topology resources created by the manager. """
        for qos_policy in self._qos_policies.values(): qos_policy.destroy()
        self._qos_policies.clear()
        for load_balancer in self._load_balancers.values(): load_balancer.destroy()
        self._load_balancers.clear()
        for health_monitor in self._health_monitors.values(): health_monitor.destroy()
        self._health_monitors.clear()
        for router in self._routers.values(): router.destroy()
        self._routers.clear()
        for bridge in self._bridges.values(): bridge.destroy()
        self._bridges.clear()
        self._bridge_router.clear()

        for chain in self._chains.values(): chain.destroy()
        self._chains.clear()

        for port_group in self._port_groups.values(): port_group.destroy()
        self._port_groups.clear()

        for vtep in self._vteps.values(): vtep.destroy()
        self._vteps.clear()

        # Missing clearing these
        self._resource_references = []
        self._links = []

    def get_device_port(self, device_name, port_id):
        """ Returns a bridge/router port for specified device and port ID. """
        if device_name in self._bridge_router:
            return self._bridge_router[device_name].get_port(port_id)
        else:
            return None

    def get_tenant_id(self):
        if self._vt.get('tenant_name'):
            ks_tenant = get_or_create_tenant(self._vt.get('tenant_name'))
            self._vt['tenant_id'] = ks_tenant.id
            return ks_tenant.id
        elif self._vt.get('tenant_id'):
            return self._vt.get('tenant_id')
        else:
            ks_tenant = get_or_create_tenant('default-tenant')
            self._vt['tenant_id'] = ks_tenant.id
            return ks_tenant.id

    def register_resource_reference(self, referrer, setter, reference_spec):
        """Registers a resource reference for lazy resolution.

        Args:
            referrer: A resource referring to another resource.
            field: A setter for the field referring to the resource.
            reference_spec: A specification of the referred resource.
        """
        self._resource_references.append(
                ResourceReference(referrer, setter, reference_spec))

    # Testing framework objects
    def add_bridge(self, bridge):
        bridge_obj = Bridge(self._api, self, bridge)
        bridge_obj.build()
        self._bridges[bridge['name']] = bridge_obj
        self._bridge_router[bridge['name']] = bridge_obj

    def get_bridge(self, name):
        return self._bridges[name]

    def add_mirror(self, mirror):
        mirror_obj = Mirror(self._api, self, mirror)
        mirror_obj.build()
        self._mirrors[mirror['name']] = mirror_obj

    def get_mirror(self, name):
        return self._mirrors[name]

    def add_router(self, router):
        router_obj = Router(self._api, self, router)
        router_obj.build()
        self._routers[router['name']] = router_obj
        self._bridge_router[router['name']] = router_obj

    def get_router(self, name):
        return self._routers[name]

    def get_bridge_router(self, name):
        return self._bridge_router[name]

    def add_chain(self, chain_data):
        chain = Chain(self._api, self, chain_data)
        chain.build()
        self._chains[chain_data['name']] = chain

    def get_chain(self, name):
        return self._chains.get(name)

    def add_tracerequest(self, tracerequest_data):
        tracerequest = TraceRequest(self._api, self, tracerequest_data)
        tracerequest.build()
        self._tracerequests[tracerequest_data['name']] = tracerequest

    def get_tracerequest(self, name):
        return self._tracerequests.get(name)

    def add_load_balancer(self, lb_data):
        load_balancer = LoadBalancer(self._api, self, lb_data)
        load_balancer.build()
        self._load_balancers[lb_data['name']] = load_balancer

    def get_load_balancer(self, lb_name):
        return self._load_balancers.get(lb_name)

    def find_pool_member(self, ip_port):
        ip, port = ip_port

        matches_found = []

        for load_balancer in self._load_balancers.values():
            for pool in load_balancer._pools:
                for member in pool._members:
                    if member.get_address() == ip and member.get_port() == port:
                        matches_found.append(member)

        if len(matches_found) == 1:
            return matches_found[0]
        elif len(matches_found) > 1:
            raise Exception("Didn't expect to find multiple matches for pool member %s" % ip_port)
        else:
            return None

    def find_vip(self, ip_port):
        ip, port = ip_port

        matches_found = []

        for load_balancer in self._load_balancers.values():
            for vip in load_balancer._vips:
                if vip.get_address() == ip and vip.get_port() == port:
                    matches_found.append(vip)

        if len(matches_found) == 1:
            return matches_found[0]
        elif len(matches_found) > 1:
            raise Exception("Didn't expect to find multiple matches for VIP %s" % ip_port)
        else:
            return None

    def add_health_monitor(self, hm_data):
        health_monitor = HealthMonitor(self._api, self, hm_data)
        health_monitor.build()
        self._health_monitors[hm_data['name']] = health_monitor

    def get_health_monitor(self, hm_name):
        return self._health_monitors.get(hm_name)

    def add_link(self, link_data):
        link = Link(self._api, self, link_data)
        self._links.append(link)
        return link

    def register_link(self, device_port, to_device_spec):
        """Registers a port linking between device port and to_device_sped."""
        link = Link(self._api, self, {})
        link.set_peer_a_name(device_port.get_device_name())
        link.set_peer_a_port_id(device_port.get_id())
        link.set_peer_b_name(to_device_spec['device'])
        link.set_peer_b_port_id(to_device_spec['port_id'])
        self._links.append(link)
        return link

    def add_port_group(self, port_group_data):
        port_group = PortGroup(self._api, self, port_group_data)
        port_group.build()
        self._port_groups[port_group_data['name']] = port_group
        return port_group

    def get_port_group(self, name):
        return self._port_groups.get(name)

    def add_vtep(self, vtep_data):
        vtep = Vtep(self._api, self, vtep_data)
        vtep.build()
        self._vteps[vtep_data['name']] = vtep
        return vtep

    def get_vtep(self, name):
        return self._vteps.get(name)

    def delete_vtep(self, name):
        self._vteps.pop(name).destroy()

    def add_qos_policy(self, qos_policy):
        qos_policy_obj = QOSPolicy(self._api, self, qos_policy)
        qos_policy_obj.build()
        self._qos_policies[qos_policy['name']] = qos_policy_obj
        return qos_policy_obj

    def get_qos_policy(self, name):
        return self._qos_policies.get(name)
