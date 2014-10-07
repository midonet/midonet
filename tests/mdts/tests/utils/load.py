"""
Load generating functions for functional tests.
"""

import logging
from random import randint
from re import search

LOG = logging.getLogger(__name__)

def run_loadgen(src_itf, dst_itf, id, count, delay):
    cmdline = '../utils/loadgen.sh {0} $peer_if {1} {2} {3}'.format(
        id, dst_itf.get_mac_addr(), count, delay)
    return src_itf.exec_interactive(cmdline)

def count_packets(itf, pcap_filter, expected_packets):
    cmdline = 'tcpdump -n -l -i $peer_if -c {0} -w /dev/null {1} 2>&1'.format(
                expected_packets, pcap_filter)
    return itf.exec_interfactive(cmdline)

def captured_packets(result):
    output, err = result.communicate()
    return int(search('(\d+) packets captured', output).group(1))

def run_nmap(rate, src_itf, dst_itf):
    cmdline = 'nmap ' + dst_itf.get_ip() + ' ' \
              '--max-retries 1 ' \
              '--max-scan-delay 1ms ' \
              '--min-rate ' + str(rate) + ' ' \
              '--max-rate ' + str(rate) + ' ' \
              '--send-ip ' \
              '-Pn ' \
              '-v ' \
              '-r ' \
              '-n ' \
              '-p1-65000 ' \
              '-g ' + str(randint(1, 65000))

    return src_itf.exec_interactive(cmdline)

def nmap_rate(result):
    output, err = result.communicate()
    LOG.info('error = ' + err)
    LOG.info('output = ' + output)
    elapsed = float(search('(\d+.\d+)s elapsed', output).group(1))
    rcv = int(search('Rcvd: (\d+)', output).group(1))
    return int((rcv * 2) / elapsed)
