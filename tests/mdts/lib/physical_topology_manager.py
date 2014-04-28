"""
Resource manager for physical topology data.
"""

import logging

from mdts.lib.interface import Interface
from mdts.lib.topology_manager import TopologyManager
from mdts.tests.utils import wait_on_futures


LOG = logging.getLogger(__name__)


class PhysicalTopologyManager(TopologyManager):

    def __init__(self, filename=None, data=None):

        super(PhysicalTopologyManager, self).__init__(filename, data)

        self._hosts = self._data['physical_topology'].get('hosts')
        self._bridges = self._data['physical_topology'].get('bridges') or []
        self._interfaces = {}  # (host_id, interface_id) to interface map

    def build(self):
        """
        Build physical topology from the data.

        Args:
            filename: filename that defines physical topology
            data: python dictionary object to represent the physical topology

        """

        LOG.debug('-' * 80)
        LOG.debug("build")
        LOG.debug('-' * 80)
        for b in self._bridges:
            bridge = b['bridge']
            # TODO(tomohiko) Need to something when not bridge['provided']?
            if bridge['provided']:
                LOG.info('Skipped building bridge=%r', bridge)

        for h in self._hosts:
            host = h['host']
            if host.get('tunnel_zone'):
                tz_data = host.get('tunnel_zone')
                tzs = self._api.get_tunnel_zones()

                # Ensure that TZ exists
                tz = [t for t in tzs if t.get_name() == tz_data['name']]
                if tz == []:
                    tz = self._api.add_gre_tunnel_zone()
                    tz.name(tz_data['name'])
                    tz.create()
                else:
                    tz = tz[0]

                # Ensure that the host is in the TZ
                tz_hosts = tz.get_hosts()
                tz_host = filter(
                    lambda x: x.get_host_id() == host['mn_host_id'],
                    tz_hosts)
                if tz_host == []:
                    tz_host = tz.add_tunnel_zone_host()
                    tz_host.ip_address(tz_data['ip_addr'])
                    tz_host.host_id(host['mn_host_id'])
                    tz_host.create()


            if host['provided'] == True:
                LOG.info('Skipped building host=%r', host)
            else:
                #TODO(tomoe): when we support provisioning Midolman host with
                # this tool.
                pass
            interfaces = host['interfaces']

            futures = []
            for i in interfaces:
                iface = Interface(i['interface'], host)
                self._interfaces[(host['id'], i['interface']['id'])] = iface
                f = iface.create()
                futures.append(f)

            wait_on_futures(futures)

        LOG.debug('-' * 80)
        LOG.debug("end build")
        LOG.debug('-' * 80)

    def destroy(self):

        LOG.debug('-' * 80)
        LOG.debug("destroy")
        LOG.debug('-' * 80)

        for h in self._hosts:
            host = h['host']

            # Delete TZ
            if host.get('tunnel_zone'):
                tz_data = host.get('tunnel_zone')
                tzs = self._api.get_tunnel_zones()
                tz = filter(lambda x: x.get_name() == tz_data['name'], tzs)
                # Delete tz, which has(have) the name in the config
                map(lambda x: x.delete(), tz)

            if host['provided'] == True:
                LOG.info('Skipped destroying host=%r', host)
            else:
                #TODO(tomoe): when we support provisioning Midolman host with
                # this tool.
                pass
            interfaces = host['interfaces']

            futures = []
            for i in interfaces:
                iface = Interface(i['interface'], host)
                f = iface.delete()
                futures.append(f)

            wait_on_futures(futures)

        for b in self._bridges:
            bridge = b['bridge']
            # TODO(tomohiko) Need to do something when !host['provided']?
            if host['provided']:
                LOG.info('Skipped destroying bridge=%r', bridge)

        LOG.debug('-' * 80)
        LOG.debug("end destroy")
        LOG.debug('-' * 80)

    def get_interface(self, host_id, interface_id):
        return self._interfaces.get((host_id, interface_id))
