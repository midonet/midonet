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

from nose.plugins import Plugin
from nose.inspector import inspect_traceback

from mdts.lib import subprocess_compat

class Mdts(Plugin):
    """
    Mdts nose plugin to collect logs upon failure.
    For plugin development, see:
    http://nose.readthedocs.org/en/latest/plugins/interface.html
    """

    name = 'mdts'
    tools_path = os.path.abspath(__file__ + '/../../../tools/logs')

    def options(self, parser, env):
        """Register commandline options.
        """
        parser.add_option(
            "--mdts-logs", action="store_true",
            dest="mdtsLogs",
            default=env.get('NOSE_MDTS_LOGS', False),
            help="Collect midonet logs upon failure")

    def configure(self, options, conf):
        """Configure which kinds of exceptions trigger plugin.
        """
        self.conf = conf
        self.enabled = options.mdtsLogs
        self.xunit_file = options.xunit_file

    def beforeTest(self, test):
        """Inserts merker to the MM logs"""
        marker = self._get_markers_for_test(test.id())['start']
        self._mark_mm_logs(marker)

    def afterTest(self, test):
        """Inserts merker to the MM logs"""
        marker = self._get_markers_for_test(test.id())['end']
        self._mark_mm_logs(marker)

    def formatFailure(self, test, err):
        """Add detail from traceback inspection and MMM logs to
           error message
        """
        ec, ev, tb = err
        tbinfo = inspect_traceback(tb)
        test.tbinfo = tbinfo

        mmm_log = self._get_per_test_logs(test)

        return (ec, '\n'.join([str(ev), tbinfo, mmm_log]), tb)

    def formatError(self, test, err):
        """Add detail from traceback inspection and MMM logs to
           error message
        """
        ec, ev, tb = err
        tbinfo = inspect_traceback(tb)
        test.tbinfo = tbinfo

        mmm_log = self._get_per_test_logs(test)

        return (ec, '\n'.join([str(ev), tbinfo, mmm_log]), tb)


    def report(self, stream):
        """Add per suite logs right at the end of a test run"""

        cmd = self.tools_path + '/' +  'dump_per_suite_logs.sh'
        out = subprocess_compat.check_output(cmd)
        stream.writeln(out)

    def finalize(self, result):
        """Finally modify xunit xml file by adding only relevant
        midolmlan logs for failed or errored tests"""

        if os.path.exists(self.xunit_file):
            tree = ET.parse(self.xunit_file)
            root = tree.getroot()

            for test_case in root:
                test_id = test_case.get(
                    'classname') + '.' + test_case.get('name')

                failure = test_case.find('failure')
                if failure is not None:
                    failure.text += '\n'
                    failure.text += self._get_midolman_logs_for_test(
                        test_id).replace('\0', '')

                error = test_case.find('error')
                if error is not None:
                    error.text += '\n'
                    error.text += self._get_midolman_logs_for_test(
                        test_id).replace('\0', '')

            tree.write(self.xunit_file)

    def _get_per_test_logs(self, test):
        """Returns mmm log in string"""
        cmd = self.tools_path + '/' +  'dump_per_test_logs.sh'
        return subprocess_compat.check_output(cmd)

    def _mark_mm_logs(self, marker_text):
        cmdline = self.tools_path + '/' +  'mark_mm_logs.sh ' + marker_text
        subprocess_compat.check_output(cmdline, shell=True)

    def _get_markers_for_test(self, test_id):
        """Returns a dict for log markers, keyed by 'start' and 'end'"""

        start = '\">>>> %s started\"' % test_id
        end = '\"<<<< %s ended\"' % test_id
        return {'start': start, 'end': end}

    def _get_midolman_logs_for_test(self, test_id):
        """Returns a string that contains midolman logs relevant
           to the test"""
        markers = self._get_markers_for_test(test_id)

        cmdline = self.tools_path + '/' +  \
            "dump_midolman_logs_for_test.sh '%s' '%s'" % (
            markers['start'], markers['end'])

        return subprocess_compat.check_output(cmdline, shell=True)

