/*------------------------------------------------------------------------------
 **     Ident: Sogeti Smart Mobile Solutions
 **    Author: rene
 ** Copyright: (c) 2015 Sogeti Nederland B.V. All Rights Reserved.
 **------------------------------------------------------------------------------
 ** Sogeti Nederland B.V.            |  No part of this file may be reproduced
 ** Distributed Software Engineering |  or transmitted in any form or by any
 ** Lange Dreef 17                   |  means, electronic or mechanical, for the
 ** 4131 NJ Vianen                   |  purpose, without the express written
 ** The Netherlands                  |  permission of the copyright holder.
 *------------------------------------------------------------------------------
 *
 *   This file is part of OpenGPSTracker.
 *
 *   OpenGPSTracker is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   OpenGPSTracker is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with OpenGPSTracker.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package nl.sogeti.android.gpstracker.settings;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;

import nl.sogeti.android.gpstracker.R;
import nl.sogeti.android.gpstracker.integration.ServiceConstants;
import nl.sogeti.android.gpstracker.integration.ServiceManager;
import nl.sogeti.android.gpstracker.streaming.StreamUtils;

public class SettingsActivity extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener {

    /**
     * The app preference for logging precession stored as String
     */
    public static final String SANITY = "speedsanitycheck";
    public static final String MONITOR = "gpsstatusmonitor";
    public static final String BOOT = "startupatboot";
    public static final String DOCK = "logatdock";
    public static final String UNDOCK = "stopatundock";
    public static final String POWER_ON = "logatpowerconnected";
    public static final String POWER_OFF = "stopatpowerdisconnected";
    public static final String STREAMING_TIME = "streambroadcast_time";
    public static final String STREAMING_DISTANCE = "streambroadcast_distance";
    private SharedPreferences mPreferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = (Toolbar) findViewById(R.id.support_actionbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        mPreferences.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mPreferences.unregisterOnSharedPreferenceChangeListener(this);
        mPreferences = null;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
        if (Helper.PRECISION_PREFERENCE.equals(key)) {
            ServiceManager.setLoggingPrecision(this,
                    Integer.valueOf(preferences.getString(Helper.PRECISION_PREFERENCE, Integer.toString(ServiceConstants.LOGGING_NORMAL))));
        } else if (Helper.CUSTOMPRECISIONTIME_PREFERENCE.equals(key) || Helper.CUSTOMPRECISIONDISTANCE_PREFERENCE.equals(key)) {
            ServiceManager.setCustomLoggingPrecision(this,
                    Integer.valueOf(preferences.getString(Helper.CUSTOMPRECISIONTIME_PREFERENCE, "1")),
                    Float.valueOf(preferences.getString(Helper.CUSTOMPRECISIONDISTANCE_PREFERENCE, "1")));
        } else if (SANITY.equals(key)) {
            ServiceManager.setSanityFilter(this,
                    preferences.getBoolean(SANITY, true));
        } else if (MONITOR.equals(key)) {
            ServiceManager.setStatusMonitor(this,
                    preferences.getBoolean(MONITOR, true));
        } else if (BOOT.equals(key) || DOCK.equals(key) || UNDOCK.equals(key) || POWER_ON.equals(key) || POWER_OFF.equals(key)) {
            ServiceManager.setAutomaticLogging(this,
                    preferences.getBoolean(BOOT, false),
                    preferences.getBoolean(DOCK, false),
                    preferences.getBoolean(UNDOCK, false),
                    preferences.getBoolean(POWER_ON, false),
                    preferences.getBoolean(POWER_OFF, false));
        } else if (Helper.BROADCAST_STREAM.equals(key) || STREAMING_TIME.equals(key) || STREAMING_DISTANCE.equals(key)) {
            ServiceManager.setStreaming(this,
                    preferences.getBoolean(Helper.BROADCAST_STREAM, false),
                    Float.valueOf(preferences.getString(SettingsActivity.STREAMING_DISTANCE, "1")),
                    Long.valueOf(preferences.getString(SettingsActivity.STREAMING_TIME, "1")));
            StreamUtils.initStreams(this.getApplicationContext());
        }
    }
}
