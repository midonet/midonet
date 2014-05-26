import subprocess

# mostly copy from Lib/subprocess.py, Python 2.7.3
def check_output(*popenargs, **kwargs):
    if 'stdout' in kwargs:
        raise ValueError('stdout argument not allowed, it will be overridden.')
    if not 'stderr' in kwargs:
        kwargs['stderr'] = subprocess.PIPE
    process = subprocess.Popen(stdout=subprocess.PIPE, *popenargs, **kwargs)
    stdout, stderr = process.communicate()
    retcode = process.poll()
    if retcode:
        cmd = kwargs.get("args")
        if cmd is None:
            cmd = popenargs[0]
        raise subprocess.CalledProcessError(retcode, str((cmd, stdout, stderr)))
    return stdout
