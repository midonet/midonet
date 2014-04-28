"""
Initialization for functional tests.
"""

from mdts.tests.config import *
from mdts.tests.utils.utils import check_all_midolman_hosts
from mdts.tests.utils.utils import clear_physical_topology
from mdts.tests.utils.utils import clear_virtual_topology_for_tenants
from mdts.tests.utils.utils import get_midonet_api

from hamcrest import *

import logging
import subprocess

LOG = logging.getLogger(__name__)


def setup():
    # disable ipv6 to avoid mac learning with automatically sent
    # ipv6 packets e.g. autoconf upon "ip link set up"
    subprocess.call(
        ['sysctl -w net.ipv6.conf.default.disable_ipv6=1 >/dev/null'],
        shell=True)

    midonet_api = get_midonet_api()
    check_all_midolman_hosts(midonet_api, True)

    clear_physical_topology()
    clear_virtual_topology_for_tenants(
            tenant_name_prefix=TEST_TENANT_NAME_PREFIX)


def teardown():
    pass
