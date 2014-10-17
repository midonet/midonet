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

from midonetclient.api import MidonetApi
from topology.bridge import Bridge
from topology.host_interface import HostInterface
from topology.router import Router
from topology.router_port import RouterPort
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

    hosts = ['00000000-0000-0000-0000-000000000001']
    addresses = ['10.0.0.8']
    if not all(hosts):
        print "host uuid file(s) is not found"
        sys.exit(1)

    api = MidonetApi('http://127.0.0.1:8080/midonet-api','admin','*')
    tx = Transaction()
    try:
        bridge = Bridge({'name':'bridge0','tenantId':tenantIds[0]})
        bridge.add(api,tx,
                   [HostInterface({'hostId': hosts[0],'name':'veth0'}),
                    HostInterface({'hostId': hosts[0],'name':'veth1'}),
                    HostInterface({'hostId': hosts[0],'name':'veth2'}),
                    HostInterface({'hostId': hosts[0],'name':'veth3'}),
                    HostInterface({'hostId': hosts[0],'name':'veth4'}),
                    HostInterface({'hostId': hosts[0],'name':'veth5'})])
        router = Router({'name':'router0','tenantId':tenantIds[0]})
        router.add(api,tx,[(RouterPort({'portAddress': '172.16.0.240',
                                        'networkAddress': '172.16.0.0',
                                        'networkLength': 24}),
                            bridge)])
        provider_router = Router({'name':'router0','tenantId':providerId})
        provider_router.add(api,tx,
                            [((RouterPort({'portAddress': '169.254.255.1',
                                           'networkAddress': '169.254.255.0',
                                           'networkLength': 30}),
                               RouterPort({'portAddress': '169.254.255.2',
                                           'networkAddress': '169.254.255.0',
                                           'networkLength': 30})),
                              router)])
    except:
        traceback.print_exc()
        tx.rollback()

    # import pdb; pdb.set_trace()
    # tx.rollback()
