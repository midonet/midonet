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

import os
import xml.etree.ElementTree as ET

from nose.plugins.base import Plugin
import sys
from StringIO import StringIO
from mdts.services import service
import shutil
import subprocess
import time
from nose.util import ln
from nose.pyversion import exc_to_unicode
from nose.pyversion import force_unicode

import logging

LOG = logging.getLogger(__name__)

class Mdts(Plugin):
    """
    Mdts nose plugin to collect logs upon failure.
    For plugin development, see:
    http://nose.readthedocs.org/en/latest/plugins/interface.html
    """

    name = 'mdts'
    enabled = True

    def options(self, parser, env):
        """
        Register commandline options.
        """
        timestamp = time.strftime("%y%m%d%H%M%S")
        parser.add_option(
            "--mdts-logs-dir", action="store",
            dest="mdts_logs_dir",
            type="str",
            default=env.get('NOSE_MDTS_LOGS_DIR', "logs/"),
            help="Directory where logs will be stored")

    def configure(self, options, conf):
        """Configure which kinds of exceptions trigger plugin.
        """
        self.conf = conf
        self.log_dir = options.mdts_logs_dir
        self.xunit_file = options.xunit_file
        self.test_markers = {}
        if os.path.exists(self.log_dir):
            shutil.rmtree(self.log_dir, ignore_errors=True)
            os.mkdir(self.log_dir)

    def beforeTest(self, test):
        """Inserts marker to the MM logs"""
        timestamp = time.strftime("%y%m%d%H%M%S")
        marker = self._get_markers_for_test(test.id(), timestamp)
        self.test_markers[test.id()] = marker
        service_hosts = self._get_all_services()
        if test.id() in self.test_markers:
            for service_host in service_hosts:
                service_host.set_log_marker(marker['start'])

    def afterTest(self, test):
        """Inserts merker to the MM logs"""
        service_hosts = self._get_all_services()
        if test.id() in self.test_markers:
            marker = self.test_markers[test.id()]
            for service_host in service_hosts:
                service_host.set_log_marker(marker['end'])

    # Methods executed before cleaning up the topology
    def formatFailure(self, test, err):
        self._write_per_test_debug_info(test, None)

    def formatError(self, test, err):
        self._write_per_test_debug_info(test, None)

    def finalize(self, test):
        """Finally modify xunit xml file by adding only relevant
        midolmlan logs for failed or errored tests"""
        if os.path.exists(self.xunit_file):
            service_hosts = self._get_all_services()
            debug_suite = False
            tree = ET.parse(self.xunit_file)
            root = tree.getroot()
            for test_case in root:
                failure = test_case.find('failure')
                if failure is None:
                    failure = test_case.find('error')

                if failure is not None:
                    test_id = "%s.%s" % (
                        test_case.get('classname'),
                        test_case.get('name')
                    )
                    debug_suite = True
                    failure.text += '\n'
                    for service_host in service_hosts:
                        self._write_service_log_files(service_host, test_id)

            if debug_suite:
                self._write_per_suite_debug_info()

            tree.write(self.xunit_file)

    def _get_all_services(self):
        flat_services = []
        services = service.get_all_containers(include_failed=True)
        for service_type, service_hosts in services.items():
            for service_host in service_hosts:
                flat_services.append(service_host)
        return flat_services

    def _write_per_suite_debug_info(self):
        service_hosts = self._get_all_services()
        for service_host in service_hosts:
            service_debug_log = service_host.get_debug_logs()
            if service_debug_log:
                with open("%s/%s-debug.log" % (
                    self.log_dir,
                    service_host.get_hostname()
                ), 'w') as f:
                    f.write(service_debug_log)

            service_full_logs = service_host.get_full_logs()
            for log_file, log_stream in service_full_logs.items():
                log_file_name = log_file.split('/')[-1]
                with open("%s/%s-%s" % (
                    self.log_dir,
                    service_host.get_hostname(),
                    log_file_name
                ), "w") as f:
                    f.write(log_stream)

            with open("%s/%s-docker.log" % (
                    self.log_dir,
                    service_host.get_hostname()
            ), 'w') as f:
                p = subprocess.Popen(
                    ["docker", "logs", service_host.get_name()],
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                output, err = p.communicate()
                f.write(output)

    def _write_per_test_debug_info(self, test, result):
        test_id = test.id()
        #test_id = "%s.%s" % (test_case.get('classname'),
        #                     test_case.get('name'))
        dump_dir = "%s/%s" % (self.log_dir,
                              test_id)
        if not os.path.exists(dump_dir):
            os.makedirs(dump_dir)

        # Any midolman works for us in here.
        # FIXME: should be zk maybe? whatever works but still
        midolmans = service.get_all_containers('midolman')
        if len(midolmans) > 0:
            midolman = midolmans[0]
            zkdump_output = midolman.exec_command(
                'zkdump -z zookeeper1:2181 -d -p')
            with open("%s/zkdump_output.log" % dump_dir, 'w') as f:
                f.write(zkdump_output)

        for midolman in midolmans:
            mmdpctl_show = midolman.exec_command(
                'mm-dpctl --timeout 10 datapath --show midonet')
            mmdpctl_dump = midolman.exec_command(
                'mm-dpctl --timeout 10 datapath --dump midonet')
            service_dir = "%s/%s" % (dump_dir, midolman.get_hostname())
            if not os.path.exists(service_dir):
                os.makedirs(service_dir)
            with open("%s/mmdpctl_output.log" % service_dir, 'w') as f:
                f.write("------------------------------------------------\n")
                f.write("mm-dpctl --timeout 10 datapath --show midonet   \n")
                f.write("------------------------------------------------\n")
                f.write(mmdpctl_show)
                f.write("------------------------------------------------\n\n")
                f.write("------------------------------------------------\n")
                f.write("mm-dpctl --timeout 10 datapath --dump midonet   \n")
                f.write("------------------------------------------------\n")
                f.write(mmdpctl_dump)

    def _write_service_log_files(self, service, test_id):
        if test_id not in self.test_markers:
            return
        marker = self.test_markers[test_id]
        service_logs = service.get_test_log(marker['start'],
                                            marker['end'])
        service_dir = "%s/%s/%s" % (self.log_dir,
                                    test_id,
                                    service.get_hostname())
        if not os.path.isdir(service_dir):
            os.makedirs(service_dir)
        for log_file, log_stream in service_logs.items():
            log_file_name = log_file.split('/')[-1]
            with open("%s/%s" % (service_dir, log_file_name), "w") as f:
                f.write(log_stream)

    def _get_markers_for_test(self, test_id, timestamp):
        """Returns a dict for log markers, keyed by 'start' and 'end'"""
        start = '>>>> %s started - timestamp %s' % (test_id, timestamp)
        end = '<<<< %s ended - timestamp %s' % (test_id, timestamp)
        return {'start': start, 'end': end}
