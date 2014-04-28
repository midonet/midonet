import os


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

    def beforeTest(self, test):
        """Inserts merker to the MM logs"""
        marker = self._get_markers_for_test(test)['start']
        self._mark_mm_logs(marker)

    def afterTest(self, test):
        """Inserts merker to the MM logs"""
        marker = self._get_markers_for_test(test)['end']
        self._mark_mm_logs(marker)

    def formatFailure(self, test, err):
        """Add detail from traceback inspection and MMM logs to
           error message
        """
        ec, ev, tb = err
        tbinfo = inspect_traceback(tb)
        test.tbinfo = tbinfo

        mmm_log = self._get_mmm_log_for_test(test)

        return (ec, '\n'.join([str(ev), tbinfo, mmm_log]), tb)

    def formatError(self, test, err):
        """Add detail from traceback inspection and MMM logs to
           error message
        """
        ec, ev, tb = err
        tbinfo = inspect_traceback(tb)
        test.tbinfo = tbinfo

        mmm_log = self._get_mmm_log_for_test(test)

        return (ec, '\n'.join([str(ev), tbinfo, mmm_log]), tb)

    def _get_mmm_log_for_test(self, test):
        """Returns mmm log in string"""
        markers = self._get_markers_for_test(test)
        cmd = self.tools_path + '/' +  'dump_logs_on_mmm.sh'
        return subprocess_compat.check_output([cmd, markers['start'], markers['end']])

    def _mark_mm_logs(self, marker_text):
        cmdline = self.tools_path + '/' +  'mark_mm_logs.sh ' + marker_text
        subprocess_compat.check_output(cmdline, shell=True)

    def _get_markers_for_test(self, test):
        """Returns a dict for log markers, keyed by 'start' and 'end'"""

        start = '\">>>> %s started\"' % test.id()
        end = '\"<<<< %s ended\"' % test.id()
        return {'start': start, 'end': end}
