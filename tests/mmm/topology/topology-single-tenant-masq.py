from midonetclient.api import MidonetApi
from topology.ad_route import AdRoute
from topology.bgp import Bgp
from topology.bridge import Bridge
from topology.chain import Chain
from topology.host_interface import HostInterface
from topology.router import Router
from topology.router_port import RouterPort
from topology.rule import RuleMasq, RuleFloatIP
from topology.tenants import get_tenant
from topology.tunnel_zone import TunnelZone
from topology.tunnel_zone_host import TunnelZoneHost
from topology.transaction import Transaction
from topology.utils import midonet_read_host_uuid
import sys
import traceback

if __name__ == '__main__':
    providerName = 'midonet_provider'
    tenantNames = ['tenant0']

    provider = get_tenant(providerName)
    if not provider:
        print "provider %r not found", providerName
        sys.exit(1)
    providerId = provider.id

    tenants = map(get_tenant,tenantNames)
    if not all(tenants):
        print "not all tenants are found"
        sys.exit(1)
    tenantIds = map(lambda tenant: tenant.id,tenants)

    hosts = ['00000000-0000-0000-0000-000000000001',
             '00000000-0000-0000-0000-000000000002',
             '00000000-0000-0000-0000-000000000003']
    addresses = ['10.0.0.8','10.0.0.9','10.0.0.10']
    if not all(hosts):
        print "host uuid file(s) is not found"
        sys.exit(1)

    api = MidonetApi('http://127.0.0.1:8080/midonet-api','admin','*')
    tx = Transaction()
    try:
        zone = TunnelZone({'name': 'zone0', 'type': 'gre'})
        zone.add(api,tx,map(lambda h,a:
                                TunnelZoneHost({'hostId': h,'ipAddress': a}),
                            hosts,addresses))
        bridge = Bridge({'name':'bridge0','tenantId':tenantIds[0]})
        bridge.add(api,tx,map(lambda h:
                                  HostInterface({'hostId': h,'name':'veth0'}),
                              hosts))

        chains = {'in': Chain({'name':'in','tenantId':tenantIds[0]}),
                  'out': Chain({'name':'out','tenantId':tenantIds[0]})}
        map(lambda key: chains[key].add(api,tx),chains)

        router = Router({'name': 'router0','tenantId': tenantIds[0],
                         'chains': chains})
        router.add(api,tx,[(RouterPort({'portAddress': '172.16.0.240',
                                        'networkAddress': '172.16.0.0',
                                        'networkLength': 24}),
                            bridge)])
        provider_router = Router({'name': 'router0','tenantId': providerId})
        provider_router\
            .add(api,tx,
                 [((RouterPort
                    ({'portAddress': '169.254.255.1',
                      'networkAddress': '169.254.255.0',
                      'networkLength': 30}),
                    RouterPort
                    ({'portAddress': '169.254.255.2',
                      'networkAddress': '169.254.255.0',
                      'networkLength': 30,
                      'rules': [RuleMasq({'snat_ip': '100.0.0.1'})]})),
                   router),
                  (RouterPort
                   ({'portAddress': '10.1.0.1',
                     'networkAddress': '10.1.0.0',
                     'networkLength': 16,
                     'bgp':
                         Bgp({'localAS': 64513,
                              'peerAS': 64512,
                              'peerAddr': '10.1.0.240',
                              'adRoute':
                                  [AdRoute({'nwPrefix': '100.0.0.0',
                                            'prefixLength': 24})]})}),
                   HostInterface({'hostId': hosts[0],'name':'eth1'}))])
    except:
        traceback.print_exc()
        tx.rollback()

    # import pdb; pdb.set_trace()
    # tx.rollback()
