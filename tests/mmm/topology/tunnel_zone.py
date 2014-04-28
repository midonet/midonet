from midonetclient.api import MidonetApi
from topology.resource_base import ResourceBase
from topology.tunnel_zone_host import TunnelZoneHost

class TunnelZone(ResourceBase):

    def __init__(self,attrs):
        self.name = attrs['name']
        self.type = attrs['type']


    def add(self,api,tx,hosts):

        zone = None
        if self.type == 'gre':
            zone = api.add_gre_tunnel_zone().name(self.name).create()
            tx.append(zone)
        elif self.type == 'capwap':
            zone = api.add_capwap_tunnel_zone().name(self.name).create()
            tx.append(zone)

        if zone:
            for host in hosts:
                tx.append(zone.add_tunnel_zone_host()\
                           .host_id(host.hostId)\
                           .ip_address(host.ipAddress).create())
