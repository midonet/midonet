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
from topology.tunnel_zone import TunnelZone
from topology.tunnel_zone_host import TunnelZoneHost
from topology.transaction import Transaction
from topology.tenants import get_tenant
from topology.utils import midonet_read_host_uuid
import sys
import traceback

if __name__ == '__main__':
    providerName = 'midonet_provider'

    provider = get_tenant(providerName)
    if not provider:
        print "provider %r not found", providerName
        sys.exit(1)
    providerId = provider.id

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
        bridge = Bridge({'name':'bridge0','tenantId':providerId})
        bridge.add(api,tx,map(lambda h:
                                  HostInterface({'hostId': h,'name':'veth0'}),
                              hosts))
    except:
        traceback.print_exc()
        tx.rollback()

    # import pdb; pdb.set_trace()
    # tx.rollback()
