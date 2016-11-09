/*    Copyright 2013-2015 ARM Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.arm.wlauto.uiauto.antutu;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import java.io.IOException;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

// Import the uiautomator libraries
import com.android.uiautomator.core.UiObject;
import com.android.uiautomator.core.UiObjectNotFoundException;
import com.android.uiautomator.core.UiScrollable;
import com.android.uiautomator.core.UiSelector;
import com.android.uiautomator.core.UiCollection;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;

import org.json.JSONObject;

import com.arm.wlauto.uiauto.BaseUiAutomation;

public class UiAutomation extends BaseUiAutomation {
    public static String TAG = "WorkloadAutomation->antutu".toString();

    public static final String PACKAGE_NAME = "com.antutu.ABenchMark";
    public static String TestButton6 = buildId("start_test_text");
    private static final int INITIAL_TIMEOUT_SEC = 20;

    public void runUiAutomation() throws Exception {
        Bundle parameters = getParams();

        final int iterations = Integer.parseInt(parameters.getString("iterations", "1"));
        final int ambientTemp = Integer.parseInt(parameters.getString("ambient-temp", "25"));
        final boolean internal = Boolean.parseBoolean(parameters.getString("internal", "true"));
        final String test = parameters.getString("test", null);


        if(test != null) {
            runTest(test, parameters);
        } else {
            if(internal) {
                runInternal(parameters);
            } else {
				runWorkload();
            }
        }
        getAutomationSupport().sendStatus(Activity.RESULT_OK, new Bundle());
    }

	public void runWorkload() throws Exception {
		dismissNewVersionNotificationIfNecessary();

		hitTestButton();
		waitForResults();
		logResults();
	}


    public void runTest(String test, Bundle parameters) throws Exception {
        if("waitForTemp".equals(test)) {
            final int ambientTemp = Integer.parseInt(parameters.getString("ambient-temp", "25"));
            Log.d(TAG, "TEST: Waiting for ambient temp: " + ambientTemp);
            waitForAmbientTemperature(ambientTemp, 30);
            Log.d(TAG, "TEST: Done");
        } else if("exec".equals(test)) {
            final String cmd = parameters.getString("cmd", null);
            
            if(cmd != null) {
                Runtime.getRuntime().exec("uiautomator-controller " + cmd);
            }
        }
    }

    public boolean dismissNewVersionNotificationIfNecessary() throws Exception {
        UiSelector selector = new UiSelector();
        UiObject closeButton = new UiObject(selector.text("Cancel"));
        if (closeButton.waitForExists(TimeUnit.SECONDS.toMillis(INITIAL_TIMEOUT_SEC))) {
            closeButton.click();
            sleep(1); // diaglog dismissal
            return true;
        } else {
            return false;
        }
    }


    public void hitTestButton() throws Exception {
        UiSelector selector = new UiSelector();
        UiObject test = new UiObject(selector.resourceId(TestButton6)
                .className("android.widget.TextView"));
        test.waitForExists(INITIAL_TIMEOUT_SEC);
        Log.d(TAG, "__MAGIC__ Starting Workload");
        test.click();
        sleep(1); // possible tab transtion
    }

    public void waitForResults() throws Exception {
        UiObject retestButton = new UiObject(new UiSelector().resourceId(buildId("btn_retest")));
        if (retestButton.waitForExists(TimeUnit.SECONDS.toMillis(500))) {
            Log.d(TAG, "__MAGIC__ Finished Workload");
            return;
        } else {
            throw new IllegalStateException("Failed to wait for btn_retest");
        }
    }

    public void logResults() throws Exception {
        long resultOverall = -1, result3d = -1, resultUx = -1, resultCpu = -1, resultRam = -1;
        //Overal result
        UiObject result = new UiObject(new UiSelector().resourceId(buildId("tv_score_name")));
        if (result.exists()) {
            resultOverall = Long.parseLong(result.getText());
            Log.v(TAG, String.format("ANTUTU RESULT: Overall Score: %s", result.getText()));
        }

        // individual scores
        result3d = extractSectionResults("3d");
        resultUx = extractSectionResults("ux");
        resultCpu = extractSectionResults("cpu");
        resultRam = extractSectionResults("ram");

        JSONObject json = new JSONObject();
        try {
            json.put("overall", resultOverall);
            json.put("3d", result3d);
            json.put("ux", resultUx);
            json.put("cpu", resultCpu);
            json.put("ram", resultRam);
            Log.i(TAG, json.toString());
        } catch(Exception e) {
            Log.e(TAG, "Failed to record JSON", e);
        }
    }

    public long extractSectionResults(String section) throws Exception {
        UiSelector selector = new UiSelector();
        UiObject resultLayout = new UiObject(selector.resourceId(buildId("hcf_" + section)));
        UiObject result = resultLayout.getChild(selector.resourceId(buildId("tv_score_value")));

        long res = -1;
        if (result.exists()) {
            res = Long.parseLong(result.getText());
            Log.v(TAG, String.format("ANTUTU RESULT: %s Score: %s", section, result.getText()));
        }
        return res;
    }

    public static String buildId(String id) {
        return String.format("%s:id/%s", PACKAGE_NAME, id);
    }

	public static String readFile(String path) throws IOException {
        BufferedReader reader = null;
        File f = new File(path);
        reader = new BufferedReader(new FileReader(f));
        String string = reader.readLine().trim();
        reader.close();
		return string;
	}

    public static int getTemp() throws IOException {
		String tempStr = readFile("/sys/class/thermal/thermal_zone5/temp");
        int temp = Integer.parseInt(tempStr);
        return temp;
    }


    public static void waitForAmbientTemperature(int ambientTemp) throws InterruptedException {
        waitForAmbientTemperature(ambientTemp, 5);
    }

    public static void waitForAmbientTemperature(int ambientTemp, int durationSec) throws InterruptedException {
        int stableCount = 0;
        long lastTime = 0;

        if (ambientTemp == 0) {
            ambientTemp = 35;
            Log.w(TAG, "Ambient temperature was 0..using: " + ambientTemp);
        }

        try {
            while (true) {
                int temp = getTemp();
                if (stableCount == durationSec) {
                    break;
                }

                long timeNow = System.currentTimeMillis();
                if (timeNow - lastTime > 30000) {
                    lastTime = timeNow;
                }

                if (temp - ambientTemp <= 0) {
                    stableCount++;
                } else {
                    stableCount = 0;
                }
                Thread.sleep(1000);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to read temperature", e);
            stableCount = 0;
        }
    }

    public static void setCoreFrequency(int frequency, int core) throws Exception {
        run("write userspace /sys/devices/system/cpu/cpu" + core + "/cpufreq/scaling_governor");
        run("write " + frequency + " /sys/devices/system/cpu/cpu" + core + "/cpufreq/scaling_setspeed");
    }

    public static void setAllCoreFrequency(int frequency) throws Exception {
        for(int core = 0; core < 4; core++) {
            run("write userspace /sys/devices/system/cpu/cpu" + core + "/cpufreq/scaling_governor");
            run("write " + frequency + " /sys/devices/system/cpu/cpu" + core + "/cpufreq/scaling_setspeed");
        }
    }

    public static void setCoreGovernor(String governor, int cpu) throws Exception {
        run(String.format("write %s /sys/devices/system/cpu/cpu%d/cpufreq/scaling_governor", governor, cpu));
    }

    public static void setAllCoreGovernor(String governor) throws Exception {
        for(int cpu = 0; cpu < 4; cpu++) {
            setCoreGovernor(governor, cpu);
        }
    }

    public static void run(String cmd) throws Exception {
        cmd = "su -vo " + cmd;
		Log.d(TAG, "$>" + cmd);
        Runtime.getRuntime().exec("uiautomator-controller " + cmd);
		Thread.sleep(300);
    }

    public void clearLogcat() throws Exception {
        run("exec logcat -- -c");
    }

	public static boolean getScreenState() throws Exception {
		int fgPid = Integer.parseInt(readFile("/proc/foreground"));
		return fgPid != 0 ? true : false;
	}

	public static void setScreenState(boolean enabled) throws Exception {
		boolean screenState = getScreenState();
		if(screenState && !enabled || !screenState && enabled) {
			run("exec -s input keyevent 26");
		}
	}

	public static void safeSleepSec(int durationSec) {
		try {
			Thread.sleep(durationSec * 1000);
		} catch(Exception e) {}
	}

    public void runInternal(Bundle parameters) throws Exception {
		final int iterations = Integer.parseInt(parameters.getString("iterations", "1"));
		final int ambientTemp = Integer.parseInt(parameters.getString("ambient-temp", "25"));
		final String outdir = parameters.getString("outdir", "/sdcard/mobisys17-cpus/AntutuWorkloadService");
		final String filename = parameters.getString("filename", "AntutuWorkloadService");

		final String finalOutdir = outdir;

		for(int iter = 0; iter < iterations; iter++) {
            String fname = String.format("%s-%03d.log", filename, iter);

			// Clear the app
			run("exec -s pm clear com.antutu.ABenchMark");

			clearLogcat();

			Log.d(TAG, "Waiting for ambient temperature");
			setAllCoreFrequency(300000);
			waitForAmbientTemperature(ambientTemp, 30);

			// Turn screen on..it may have gone off
			setScreenState(true);
			safeSleepSec(3);

			// Go to home screen
			try {
				run("exec -s input keyevent 3");
			} catch(Exception e) {
				Log.e(TAG, "Failed to press home button", e);
				// This is non-fatal..just ignore it
			}

			// We need to get into ondemand governors for the actual test
			setAllCoreGovernor("ondemand");

			// Start the app
			run("exec -s am -- start -W -n com.antutu.ABenchMark/.ABenchMarkStart");

			Log.d(TAG, "Starting workload");
			runWorkload();

			safeSleepSec(10);

			String logcatCmd = 
				String.format("exec -s logcat -- -v tracetime -f %s/%s -r 102400 -n 1000 -d",
						finalOutdir, fname);
			run(logcatCmd);

		}
    }
}
