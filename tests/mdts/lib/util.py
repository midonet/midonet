"""
Provides utility functions.
"""


def ping4_cmd(ipv4_addr, interval=0.5, count=3, size=56):
    """Constructs a ping command line to an IPv4 address."""
    return 'ping -i {0} -c {1} -s {2} {3}'.format(interval,
                                                  count,
                                                  size,
                                                  ipv4_addr)