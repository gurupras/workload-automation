# pylint: disable=W0613,E1101
from __future__ import division
import os
import sys
import time
import csv
import json
import shutil
import threading
import errno
import tempfile
import subprocess

from distutils.version import LooseVersion

from wlauto import Instrument, Parameter, IterationResult
from wlauto.instrumentation import instrument_is_installed
from wlauto.exceptions import (InstrumentError, WorkerThreadError, ConfigError,
                               DeviceNotRespondingError, TimeoutError)
from wlauto.utils.types import boolean, numeric, list_of_strs

from cpuprof import parse_logcat


DEFAULT_EVENTS=[
    'phonelab_info',
    'phonelab_periodic_warning_cpu',
    'phonelab_num_online_cpus',
    'phonelab_periodic_lim_exceeded',
    'phonelab_proc_foreground',
    'phonelab_periodic_ctx_switch_info',
    'phonelab_periodic_ctx_switch_marker',
    'sched_cpu_hotplug',
    'cpufreq_scaling',
    'cpu_frequency',
    'kgsl_gpubusy',
    'kgsl_pwrlevel',
    'thermal_temp',
    'optimal_freq',
    'tempfreq_hotplug',
    'tempfreq_binary_diff',
    'tempfreq_cgroup_copy_tasks',
    'tempfreq_hotplug',
    'tempfreq_hotplug_autosmp_rates',
    'tempfreq_hotplug_nr_running',
    'tempfreq_hotplug_state',
    'tempfreq_hotplug_target',
    'tempfreq_mpdecision_blocked',
    'tempfreq_temp',
    'tempfreq_thermal_bg_throttling_proc',
    'tempfreq_thermal_cgroup_throttling',
    'tempfreq_timing',
]

class TracingInstrument(Instrument):

    name = 'trace'
    description = """
    Measures trace

    The view is specified by the workload as ``view`` attribute. This defaults
    to ``'SurfaceView'`` for game workloads, and ``None`` for non-game
    workloads (as for them Tracing mesurement usually doesn't make sense).
    Individual workloads may override this.

    This instrument adds one new metric to the results:

        :trace: The reading from the trace sensor

    """
    supported_platforms = ['android']

    parameters = [
        Parameter('events', kind=list_of_strs, default=DEFAULT_EVENTS,
                  description="""
                  Specifies the time period at which the trace sensor
                  will be polled.
                  """),
    ]

    def __init__(self, device, **kwargs):
        super(TracingInstrument, self).__init__(device, **kwargs)
        self.collector = None
        self.trace_outfile = None
        self.is_enabled = True
    def validate(self):
        pass

    def setup(self, context):
        workload = context.workload
        self.trace_outfile = os.path.join(context.output_directory, 'trace.log')
        self.collector = TracingCollector(self.events, self.trace_outfile, self.device, self.logger)
        self.events = set(DEFAULT_EVENTS + self.events)
        for event in self.events:
            self.device.execute('trace enable {}'.format(event))

    def start(self, context):
        if self.is_enabled:
            self.logger.debug('Starting trace collection...')
            self.collector.start()

    def stop(self, context):
        if self.is_enabled and self.collector.is_alive():
            self.logger.debug('Stopping trace collection...')
            self.collector.stop()

    def update_result(self, context):
        pass


class TracingCollector(threading.Thread):

    def __init__(self, events, outfile, device, logger):
        super(TracingCollector, self).__init__()
        self.outfile = outfile
        self.device = device
        self.logger = logger
        self.events = events
        self.stop_signal = threading.Event()
        self.exc = None
        self.traces = []
        self.unresponsive_count = 0

    def run(self):
        try:
            self.logger.debug('Tracing collection started.')
            self.stop_signal.clear()
            fd, trace_file = tempfile.mkstemp()
            self.logger.debug('trace file: {}'.format(trace_file))

            deviceid = self.device.adb_name
            cmd = 'adb -s {} logcat -v tracetime Kernel-Trace:* *:S'.format(deviceid)
            with open(self.outfile, 'wb') as wfh:
                try:
                    self.process = subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                    while not self.stop_signal.is_set():
                        stdout = iter(self.process.stdout.readline, "")
                        for line in stdout:
                            logline = parse_logcat.process_line(line)
                            if logline is None:
                                self.logger.warn("Was not parsed: '{}'".format(line))
                                continue
                            wfh.write(line)
                finally:
                    pass
        # TODO: this can happen after the run during results processing
        except (DeviceNotRespondingError, TimeoutError):  # pylint: disable=W0703
            raise
        except Exception, e:  # pylint: disable=W0703
            self.logger.warning('Exception on collector thread: {}({})'.format(e.__class__.__name__, e))
            self.exc = WorkerThreadError(self.name, sys.exc_info())
        self.logger.debug('Tracing collection stopped.')

        self.logger.debug('Tracing data written.')

    def stop(self):
        self.stop_signal.set()
        self.join()
        self.process.terminate()
        if self.unresponsive_count:
            message = 'Tracing was unrepsonsive {} times.'.format(self.unresponsive_count)
            if self.unresponsive_count > 10:
                self.logger.warning(message)
            else:
                self.logger.debug(message)
        if self.exc:
            raise self.exc  # pylint: disable=E0702
        self.logger.debug('Tracing collection complete.')

