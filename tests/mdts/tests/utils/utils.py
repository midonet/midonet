"""
Utility functions for functional tests.
"""

from hamcrest import assert_that
from hamcrest import is_

from midonetclient.api import MidonetApi

from mdts.lib.tenants import list_tenants
from mdts.tests.config import MIDONET_API_URL

import inspect
import logging
import os
import subprocess
import time

from mdts.lib import subprocess_compat

FORMAT = '%(asctime)-15s %(module)s#%(funcName)s(%(lineno)d) %(message)s'
logging.basicConfig(format=FORMAT, level=logging.DEBUG)


def wait_on_futures(futures):
    """ Takes a list of futures and wait on their results. """
    return map(lambda f: f.result(), futures)


def get_midonet_api():
    return MidonetApi(MIDONET_API_URL, 'admin','*')

#
# ``failures'' introduces another decorator @failures which can be used to
# inject various failures, combined with @bindings, in existing test cases.
# Note: @failures doesn't work alone and @failures (if any) has to be placed
# before @bindings, otherwise type mismatch error occurs.
#
# Example #1:
#
# @failures(NoFailure())
# @bindings(bindings1, bindings2)
# def test_foo():
#     [...]
#
# 'NoFailure' is a pseudo failure embracing the notion of "without failure."
# test_foo runs twice, with bindings1 and with bindings2, which is basically
# the same as the test without @failures,
#
# @bindings(bindings1, bindings2)
# def test_foo():
#     [...]
#
#
# Example #2:
#
# @failures(NoFailure(), NetifFailure('ns002', 'eth0', 30))
# @bindings(bindings1, bindings2)
# def test_foo():
#     [...]
#
# 'NetifFailure' emulates a failure in network interface. See
# qa/mdts/lib/failure/netif_failure.py for further details.
# In this setting, test_foo runs four times, enumerating all the combination
# of failures and bindings, say, with bindings1 (no failure), with bindings2
# (no failure), with bindings1 (under the failure) and with bindings2 (under
# the failure).
#
# Various failures are found in qa/mdts/lib/failure. Here is the list of
# available failures right now. See the source code for the full description
# and current status of each failure.
#
#     NoFailure        no failure
#     NetifFailure     set network interface down
#     PktFailure       filter incoming packets out using iptables
#     DDoSFailure      run DDoS attach using bonesi
#     ScanFailure      run host/port scan using nmap
#     CombinedFailure  make multiple failures happen simultaneously
#

# list -> (() -> generator (() -> ())) -> () -> generator (() -> ())
def failures(*args):
    failures = args

    def test_method_wrapper(f):
        def test_wrapped():
            for failure in failures:
                failure.inject()
                try:
                    if failure.is_resilient():
                        for g in f():
                            test_wrapped.__name__ = f.__name__ + \
                                ' (with_failure %s)' % failure.__name__
                            yield g
                finally:
                    failure.eject()
        # copied from nose.tools.make_decorator to preserve metadata
        test_wrapped.__dict__ = f.__dict__
        test_wrapped.__module__ = f.__module__
        if not hasattr(test_wrapped, 'compat_co_firstlineno'):
            test_wrapped.compat_co_firstlineno = f.func_code.co_firstlineno
        return test_wrapped
    return test_method_wrapper

# list -> (() -> ()) -> () -> generator (() -> ())
def bindings(*args):
    bindings = args
    # FRAME, FILE_NAME, LINE_NUM, FUNCTION_NAME, LINES, INDEX
    (frame, _, _, _, _, _) = inspect.getouterframes(inspect.currentframe())[1]
    test_mod = inspect.getmodule(frame)
    BM = test_mod.BM

    def test_method_wrapper(f):
        def test_wrapped():
            for b in bindings:
                BM.bind(data=b)
                time.sleep(1)
                test_wrapped.__name__ = f.__name__ + ' (with_bindings %s)' \
                                        % b.get('description')
                yield f,
                BM.unbind()
        # copied from nose.tools.make_decorator to preserve metadata
        test_wrapped.__dict__ = f.__dict__
        test_wrapped.__module__ = f.__module__
        if not hasattr(test_wrapped, 'compat_co_firstlineno'):
            test_wrapped.compat_co_firstlineno = f.func_code.co_firstlineno
        return test_wrapped
    return test_method_wrapper


def clear_virtual_topology_for_tenants(tenant_name_prefix):
    """
    Delete the virtual topology for tenants whose name starts
    with tenant_name_prefix

    Args:
        tenant_name_prefix: the prefix of the tenant name
    """

    api = get_midonet_api()
    tenants = filter(lambda x: x.name.startswith(tenant_name_prefix),
                     list_tenants())

    for tenant in tenants:
        # delete routers
        for router in api.get_routers({'tenant_id': tenant.id}):
            # unlink and delete all router ports
            for port in router.get_ports():
                if port.get_type() == 'InteriorRouter':
                    if port.get_peer_id():
                        port.unlink()
                port.delete()
            # delete router
            router.delete()

        # delete bridges
        for bridge in api.get_bridges({'tenant_id': tenant.id}):
            # unlink and delete all bridge ports
            for port in bridge.get_ports():
                if port.get_type() == 'InteriorBridge':
                    if port.get_peer_id():
                        port.unlink()
                port.delete()
            # delete router
            bridge.delete()

        # delete chains
        for chain in api.get_chains({'tenant_id': tenant.id}):
            for rule in chain.get_rules():
                rule.delete()
            chain.delete()


def clear_physical_topology():
    logging.debug('-' * 80)
    logging.debug("clear")
    logging.debug('-' * 80)

    logging.debug("clear physical topology")

    def _clear():
        cmdline = ("for ns in `ip netns | grep -- ns-if-`; "
                   "do sudo -i ip netns delete $ns 2>/dev/null; done")
        logging.debug("cmdline: " + cmdline)
        try:
            subprocess_compat.check_output(cmdline, shell=True)
        except subprocess.CalledProcessError:
            pass

        cmdline = ("for iface in `ip link | grep -- if- | cut -d ':' -f 2`; "
                   "do sudo -i ip link delete $iface 2>/dev/null; done")
        logging.debug("cmdline: " + cmdline)
        try:
            subprocess_compat.check_output(cmdline, shell=True)
        except subprocess.CalledProcessError:
            pass

    _clear()
    _clear()
    _clear()

    logging.debug('-' * 80)
    logging.debug("end clear")
    logging.debug('-' * 80)


def ipv4_int(ipv4_str):
    """Returns an integer corresponding to the given ipV4 address."""
    parts = ipv4_str.split('.')
    if len(parts) != 4:
        raise Exception('Incorrect IPv4 address format: %s' % ipv4_str)

    addr_int = 0
    for part in parts:
        part_int = 0
        try:
            part_int = int(part)
        except:
            raise Exception('Incorrect IPv4 address format: %s' % ipv4_str)
        addr_int = addr_int * 256 + part_int
    return addr_int

def get_top_dir():
    """Returns the top dir of the midonet repo"""
    topdir = os.path.realpath(os.path.dirname(__file__) + '../../../../../')
    return topdir

def get_midolman_script_dir():
    """Returns abs path to Midolman scripts directory"""
    return get_top_dir() + '/tests/mmm/scripts/midolman'


def start_midolman_agents():
    """Starts all Midolman agents."""
    subprocess_compat.check_output(
        'cd %s; ./start' % get_midolman_script_dir(), shell=True)


def stop_midolman_agents():
    """Stops all Midolman agents."""
    subprocess_compat.check_output(
        'cd %s; ./stop' % get_midolman_script_dir(), shell=True)


def check_all_midolman_hosts(midonet_api, alive):
    for h in  midonet_api.get_hosts():
        assert_that(h.is_alive(), is_(alive),
                    'A Midolman %s is alive.' % h.get_id())
