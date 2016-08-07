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

from distutils.version import LooseVersion

from wlauto import Instrument, Parameter, IterationResult
from wlauto.instrumentation import instrument_is_installed
from wlauto.exceptions import (InstrumentError, WorkerThreadError, ConfigError,
                               DeviceNotRespondingError, TimeoutError)
from wlauto.utils.types import boolean, numeric



TEMP_READ_COMMAND = 'cat /sys/class/thermal/thermal_zone5/temp'


class TemperatureInstrument(Instrument):

    name = 'temperature'
    description = """
    Measures temperature

    The view is specified by the workload as ``view`` attribute. This defaults
    to ``'SurfaceView'`` for game workloads, and ``None`` for non-game
    workloads (as for them FPS mesurement usually doesn't make sense).
    Individual workloads may override this.

    This instrument adds one new metric to the results:

        :temperature: The reading from the temperature sensor

    """
    supported_platforms = ['android']

    parameters = [
        Parameter('period_ms', kind=int, default=250,
                  description="""
                  Specifies the time period at which the temperature sensor
                  will be polled.
                  """),
    ]

    def __init__(self, device, **kwargs):
        super(TemperatureInstrument, self).__init__(device, **kwargs)
        self.collector = None
        self.temp_outfile = None
        self.is_enabled = True

    def validate(self):
        pass

    def setup(self, context):
        workload = context.workload
        self.temp_outfile = os.path.join(context.output_directory, 'temp.json')
        self.collector = TemperatureCollector(self.period_ms, self.temp_outfile, self.device, self.logger)

    def start(self, context):
        if self.is_enabled:
            self.logger.debug('Starting temperature collection...')
            self.collector.start()

    def stop(self, context):
        if self.is_enabled and self.collector.is_alive():
            self.logger.debug('Stopping temperature collection...')
            self.collector.stop()

    def update_result(self, context):
        pass


class TemperatureCollector(threading.Thread):

    def __init__(self, period, outfile, device, logger):
        super(TemperatureCollector, self).__init__()
        self.outfile = outfile
        self.device = device
        self.logger = logger
        self.period = period / 1000.0
        self.stop_signal = threading.Event()
        self.exc = None
        self.temps = []
        self.unresponsive_count = 0

    def run(self):
        try:
            self.logger.debug('Temperature collection started.')
            self.stop_signal.clear()
            fd, temp_file = tempfile.mkstemp()
            self.logger.debug('temp file: {}'.format(temp_file))

            #cmd = '''echo `cat /proc/uptime` `{}`'''.format(TEMP_READ_COMMAND)
            cmd = TEMP_READ_COMMAND
            try:
                while not self.stop_signal.is_set():
                    uptime, temp = (time.time(), self.device.execute(cmd))
                    self.temps.append(('{}'.format(uptime), temp))
                    time.sleep(self.period)
            finally:
                pass
            # TODO: this can happen after the run during results processing
        except (DeviceNotRespondingError, TimeoutError):  # pylint: disable=W0703
            raise
        except Exception, e:  # pylint: disable=W0703
            self.logger.warning('Exception on collector thread: {}({})'.format(e.__class__.__name__, e))
            self.exc = WorkerThreadError(self.name, sys.exc_info())
        self.logger.debug('Temperature collection stopped.')

        with open(self.outfile, 'wb') as wfh:
            wfh.write(json.dumps(self.temps, indent=2))
        self.logger.debug('Temperature data written.')

    def stop(self):
        self.stop_signal.set()
        self.join()
        if self.unresponsive_count:
            message = 'Temperature was unrepsonsive {} times.'.format(self.unresponsive_count)
            if self.unresponsive_count > 10:
                self.logger.warning(message)
            else:
                self.logger.debug(message)
        if self.exc:
            raise self.exc  # pylint: disable=E0702
        self.logger.debug('Temperature collection complete.')

