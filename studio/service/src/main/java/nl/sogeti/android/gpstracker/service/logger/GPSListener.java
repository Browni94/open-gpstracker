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
package nl.sogeti.android.gpstracker.service.logger;

import android.app.Service;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.location.GpsSatellite;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;

import java.util.Date;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Vector;

import nl.sogeti.android.gpstracker.integration.ExternalConstants;
import nl.sogeti.android.gpstracker.integration.GPSLoggerServiceManager;
import nl.sogeti.android.gpstracker.service.R;
import nl.sogeti.android.gpstracker.integration.GPStracking;
import nl.sogeti.android.log.Log;

public class GPSListener implements LocationListener, GpsStatus.Listener {

    /**
     * <code>MAX_REASONABLE_SPEED</code> is about 324 kilometer per hour or 201
     * mile per hour.
     */
    private static final int MAX_REASONABLE_SPEED = 90;
    /**
     * <code>MAX_REASONABLE_ALTITUDECHANGE</code> between the last few waypoints
     * and a new one the difference should be less then 200 meter.
     */
    private static final int MAX_REASONABLE_ALTITUDECHANGE = 200;
    private final Service mService;
    private final LoggerNotification mLoggerNotification;
    private final LoggerPersistence mPersistence;
    private Location mPreviousLocation;
    private boolean mStartNextSegment;
    private LocationManager mLocationManager;
    /**
     * If speeds should be checked to sane values
     */
    private boolean mSpeedSanityCheck;

    /**
     * If broadcasts of location about should be sent to stream location
     */
    private boolean mStreamBroadcast;
    /**
     * <code>mAcceptableAccuracy</code> indicates the maximum acceptable accuracy
     * of a waypoint in meters.
     */
    private float mMaxAcceptableAccuracy = 20;

    private float mDistance = -1;
    private int mPrecision = -1;
    private long mTrackId = -1;
    private long mSegmentId = -1;
    private long mWaypointId = -1;
    private int mLoggingState = ExternalConstants.STATE_STOPPED;
    /**
     * Should the GPS Status monitor update the mLoggerNotification bar
     */
    private boolean mStatusMonitor;
    /**
     * Number of milliseconds that a functioning GPS system needs to provide a
     * location. Calculated to be either 120 seconds or 4 times the requested
     * period, whichever is larger.
     */
    private long mCheckPeriod;
    private float mBroadcastDistance;
    private long mLastTimeBroadcast;

    private Vector<Location> mWeakLocations;
    private Queue<Double> mAltitudes;
    private String mProvider;
    private PowerManager mPowerManager;

    public GPSListener(GPSLoggerService gpsLoggerService, LoggerPersistence persistence, LoggerNotification loggerNotification, PowerManager powerManager) {
        mService = gpsLoggerService;
        mPersistence = persistence;
        mLoggerNotification = loggerNotification;
        mPowerManager = powerManager;
    }

    public void onCreate() {
        mWeakLocations = new Vector<>(3);
        mAltitudes = new LinkedList<>();
        mLoggingState = ExternalConstants.STATE_STOPPED;
        mStartNextSegment = false;
        mLocationManager = (LocationManager) mService.getSystemService(Context.LOCATION_SERVICE);
        mSpeedSanityCheck = mPersistence.isSpeedChecked();
        mStreamBroadcast = mPersistence.getStreamBroadcast();

        crashRestoreState();
    }

    public void onDestroy() {
        mPowerManager.release();
        mLocationManager.removeGpsStatusListener(this);
        stopListening();
    }

    @Override
    public void onLocationChanged(Location location) {
        Log.v(this, "onLocationChanged( Location " + location + " )");
        // Might be claiming GPS disabled but when we were paused this changed and this location proves so
        if (mLoggerNotification.isShowingDisabled()) {
            mLoggerNotification.stopDisabledProvider(R.string.service_gpsenabled);
        }
        Location filteredLocation = locationFilter(location);
        if (filteredLocation != null) {
            if (mStartNextSegment) {
                mStartNextSegment = false;
                // Obey the start segment if the previous location is unknown or far away
                if (mPreviousLocation == null || filteredLocation.distanceTo(mPreviousLocation) > 4 *
                        mMaxAcceptableAccuracy) {
                    startNewSegment();
                }
            } else if (mPreviousLocation != null) {
                mDistance += mPreviousLocation.distanceTo(filteredLocation);
            }
            storeLocation(filteredLocation);
            broadcastLocation(filteredLocation);
            mPreviousLocation = location;
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        Log.d(this, "onStatusChanged( String " + provider + ", int " + status + ", Bundle " + extras + " )");
        if (status == LocationProvider.OUT_OF_SERVICE) {
            Log.e(this, String.format("Provider %s changed to status %d", provider, status));
        }
    }

    @Override
    public void onProviderEnabled(String provider) {
        Log.d(this, "onProviderEnabled( String " + provider + " )");
        if (mPrecision != ExternalConstants.LOGGING_GLOBAL && provider.equals(LocationManager.GPS_PROVIDER)) {
            mLoggerNotification.stopDisabledProvider(R.string.service_gpsenabled);
            mStartNextSegment = true;
        } else if (mPrecision == ExternalConstants.LOGGING_GLOBAL && provider.equals(LocationManager.NETWORK_PROVIDER)) {
            mLoggerNotification.stopDisabledProvider(R.string.service_dataenabled);
        }
    }

    @Override
    public void onProviderDisabled(String provider) {
        Log.d(this, "onProviderDisabled( String " + provider + " )");
        if (mPrecision != ExternalConstants.LOGGING_GLOBAL && provider.equals(LocationManager.GPS_PROVIDER)) {
            mLoggerNotification.startDisabledProvider(R.string.service_gpsdisabled, mTrackId);
        } else if (mPrecision == ExternalConstants.LOGGING_GLOBAL && provider.equals(LocationManager.NETWORK_PROVIDER)) {
            mLoggerNotification.startDisabledProvider(R.string.service_datadisabled, mTrackId);
        }

    }

    @Override
    public synchronized void onGpsStatusChanged(int event) {
        switch (event) {
            case GpsStatus.GPS_EVENT_SATELLITE_STATUS:
                if (mStatusMonitor) {
                    GpsStatus status = mLocationManager.getGpsStatus(null);
                    mLoggerNotification.numberOfSatellites = 0;
                    Iterable<GpsSatellite> list = status.getSatellites();
                    for (GpsSatellite satellite : list) {
                        if (satellite.usedInFix()) {
                            mLoggerNotification.numberOfSatellites++;
                        }
                    }
                    mLoggerNotification.updateLogging(mPrecision, mLoggingState, mStatusMonitor, mTrackId);
                }
                break;
            case GpsStatus.GPS_EVENT_STOPPED:
                break;
            case GpsStatus.GPS_EVENT_STARTED:
                break;
            default:
                break;
        }
    }

    public void onPreferenceChange() {
        sendRequestLocationUpdatesMessage();
        crashProtectState();
        mLoggerNotification.updateLogging(mPrecision, mLoggingState, mStatusMonitor, mTrackId);
        broadCastLoggingState();
        mLocationManager.removeGpsStatusListener(this);
        sendRequestStatusUpdateMessage();
        mLoggerNotification.updateLogging(mPrecision, mLoggingState, mStatusMonitor, mTrackId);
        mStreamBroadcast = mPersistence.getStreamBroadcast();
    }

    private void sendRequestLocationUpdatesMessage() {
        stopListening();
        mStatusMonitor = mPersistence.isStatusMonitor();
        mPrecision = mPersistence.getPrecision();
        long intervalTime;
        float distance, accuracy;
        switch (mPrecision) {
            case (ExternalConstants.LOGGING_FINE): // Fine
                accuracy = LoggingConstants.FINE_ACCURACY;
                intervalTime = LoggingConstants.FINE_INTERVAL;
                distance = LoggingConstants.FINE_DISTANCE;
                startListening(LocationManager.GPS_PROVIDER, intervalTime, distance, accuracy);
                break;
            case (ExternalConstants.LOGGING_NORMAL): // Normal
                accuracy = LoggingConstants.NORMAL_ACCURACY;
                intervalTime = LoggingConstants.NORMAL_INTERVAL;
                distance = LoggingConstants.NORMAL_DISTANCE;
                startListening(LocationManager.GPS_PROVIDER, intervalTime, distance, accuracy);
                break;
            case (ExternalConstants.LOGGING_COARSE): // Coarse
                accuracy = LoggingConstants.COARSE_ACCURACY;
                intervalTime = LoggingConstants.COARSE_INTERVAL;
                distance = LoggingConstants.COARSE_DISTANCE;
                startListening(LocationManager.GPS_PROVIDER, intervalTime, distance, accuracy);
                break;
            case (ExternalConstants.LOGGING_GLOBAL): // Global
                accuracy = LoggingConstants.GLOBAL_ACCURACY;
                intervalTime = LoggingConstants.GLOBAL_INTERVAL;
                distance = LoggingConstants.GLOBAL_DISTANCE;
                startListening(LocationManager.NETWORK_PROVIDER, intervalTime, distance, accuracy);
                if (!isNetworkConnected()) {
                    mLoggerNotification.startDisabledProvider(R.string.service_connectiondisabled, mTrackId);
                }
                break;
            case (ExternalConstants.LOGGING_CUSTOM): // Global
                intervalTime = mPersistence.getCustomLocationIntervalSeconds() * 1000;
                distance = mPersistence.getCustomLocationIntervalMetres();
                accuracy = Math.max(10f, Math.min(distance, 50f));
                startListening(LocationManager.GPS_PROVIDER, intervalTime, distance, accuracy);
                break;
            default:
                Log.e(this, "Unknown precision " + mPrecision);
                break;
        }
    }

    private boolean isNetworkConnected() {
        ConnectivityManager connMgr = (ConnectivityManager) mService.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = connMgr.getActiveNetworkInfo();

        return (info != null && info.isConnected());
    }

    /**
     * Some GPS waypoints received are of to low a quality for tracking use. Here
     * we filter those out.
     *
     * @param proposedLocation
     * @return either the (cleaned) original or null when unacceptable
     */
    public Location locationFilter(Location proposedLocation) {
        // Do no include log wrong 0.0 lat 0.0 long, skip to next value in while-loop
        if (proposedLocation != null && (proposedLocation.getLatitude() == 0.0d
                || proposedLocation.getLongitude() == 0.0d)) {
            Log.w(this, "A wrong location was received, 0.0 latitude and 0.0 longitude... ");
            proposedLocation = null;
        }

        // Do not log a waypoint which is more inaccurate then is configured to be acceptable
        if (proposedLocation != null && proposedLocation.getAccuracy() > mMaxAcceptableAccuracy) {
            Log.w(this, String.format("A weak location was received, lots of inaccuracy... (%f is more then max %f)",
                    proposedLocation.getAccuracy(),
                    mMaxAcceptableAccuracy));
            proposedLocation = addBadLocation(proposedLocation);
        }

        // Do not log a waypoint which might be on any side of the previous waypoint
        if (proposedLocation != null && mPreviousLocation != null && proposedLocation.getAccuracy() > mPreviousLocation
                .distanceTo(proposedLocation)) {
            Log.w(this,
                    String.format("A weak location was received, not quite clear from the previous waypoint... (%f more " +
                                    "then max %f)",
                            proposedLocation.getAccuracy(), mPreviousLocation.distanceTo(proposedLocation)));
            proposedLocation = addBadLocation(proposedLocation);
        }

        // Speed checks, check if the proposed location could be reached from the previous one in sane speed
        // Common to jump on network logging and sometimes jumps on Samsung Galaxy S type of devices
        if (mSpeedSanityCheck && proposedLocation != null && mPreviousLocation != null) {
            // To avoid near instant teleportation on network location or glitches cause continent hopping
            float meters = proposedLocation.distanceTo(mPreviousLocation);
            long seconds = (proposedLocation.getTime() - mPreviousLocation.getTime()) / 1000L;
            float speed = meters / seconds;
            if (speed > MAX_REASONABLE_SPEED) {
                Log.w(this, "A strange location was received, a really high speed of " + speed + " m/s, prob wrong...");
                proposedLocation = addBadLocation(proposedLocation);
                // Might be a messed up Samsung Galaxy S GPS, reset the logging
                if (speed > 2 * MAX_REASONABLE_SPEED && mPrecision != ExternalConstants.LOGGING_GLOBAL) {
                    Log.w(this, "A strange location was received on GPS, reset the GPS listeners");
                    stopListening();
                    mLocationManager.removeGpsStatusListener(this);
                    mLocationManager = (LocationManager) mService.getSystemService(Context.LOCATION_SERVICE);
                    sendRequestStatusUpdateMessage();
                    sendRequestLocationUpdatesMessage();
                }
            }
        }

        // Remove speed if not sane
        if (mSpeedSanityCheck && proposedLocation != null && proposedLocation.getSpeed() > MAX_REASONABLE_SPEED) {
            Log.w(this, "A strange speed, a really high speed, prob wrong...");
            proposedLocation.removeSpeed();
        }

        // Remove altitude if not sane
        if (mSpeedSanityCheck && proposedLocation != null && proposedLocation.hasAltitude()) {
            if (!addSaneAltitude(proposedLocation.getAltitude())) {
                Log.w(this, "A strange altitude, a really big difference, prob wrong...");
                proposedLocation.removeAltitude();
            }
        }
        // Older bad locations will not be needed
        if (proposedLocation != null) {
            mWeakLocations.clear();
        }
        return proposedLocation;
    }

    /**
     * Trigged by events that start a new segment
     */
    private void startNewSegment() {
        this.mPreviousLocation = null;
        Uri newSegment = mService.getContentResolver().insert(Uri.withAppendedPath(GPStracking.Tracks.CONTENT_URI, mTrackId +
                "/segments"), new ContentValues(0));
        mSegmentId = Long.valueOf(newSegment.getLastPathSegment()).longValue();
        crashProtectState();
    }

    protected boolean isLogging() {
        return mLoggingState == ExternalConstants.STATE_LOGGING;
    }

    /**
     * Use the ContentResolver mechanism to store a received location
     *
     * @param location
     */
    public void storeLocation(Location location) {
        if (!isLogging() || mTrackId < 0 || mSegmentId < 0) {
            Log.e(this, String.format("Storing location without Logging (%b) or track (%d,%d).", isLogging(), mTrackId, mSegmentId));
            return;
        }
        ContentValues args = new ContentValues();

        args.put(GPStracking.Waypoints.LATITUDE, Double.valueOf(location.getLatitude()));
        args.put(GPStracking.Waypoints.LONGITUDE, Double.valueOf(location.getLongitude()));
        args.put(GPStracking.Waypoints.SPEED, Float.valueOf(location.getSpeed()));
        args.put(GPStracking.Waypoints.TIME, Long.valueOf(System.currentTimeMillis()));
        if (location.hasAccuracy()) {
            args.put(GPStracking.Waypoints.ACCURACY, Float.valueOf(location.getAccuracy()));
        }
        if (location.hasAltitude()) {
            args.put(GPStracking.Waypoints.ALTITUDE, Double.valueOf(location.getAltitude()));

        }
        if (location.hasBearing()) {
            args.put(GPStracking.Waypoints.BEARING, Float.valueOf(location.getBearing()));
        }

        Uri waypointInsertUri = Uri.withAppendedPath(GPStracking.Tracks.CONTENT_URI, mTrackId + "/segments/" + mSegmentId +
                "/waypoints");
        Uri inserted = mService.getContentResolver().insert(waypointInsertUri, args);
        mWaypointId = Long.parseLong(inserted.getLastPathSegment());
    }

    /**
     * Consult broadcast options and execute broadcast if necessary
     *
     * @param location
     */
    private void broadcastLocation(Location location) {
        Intent intent = new Intent(ExternalConstants.STREAM_BROADCAST);

        if (mStreamBroadcast) {
            float minDistance = mPersistence.getBroadcastIntervalMeters();
            long minTime = mPersistence.getBroadcastIntervalMinutes();
            final long nowTime = location.getTime();
            if (mPreviousLocation != null) {
                mBroadcastDistance += location.distanceTo(mPreviousLocation);
            }
            if (mLastTimeBroadcast == 0) {
                mLastTimeBroadcast = nowTime;
            }
            long passedTime = (nowTime - mLastTimeBroadcast);
            passedTime = passedTime / 60000;
            intent.putExtra(ExternalConstants.EXTRA_DISTANCE, (int) mBroadcastDistance);
            intent.putExtra(ExternalConstants.EXTRA_TIME, (int) passedTime);
            intent.putExtra(ExternalConstants.EXTRA_LOCATION, location);
            intent.putExtra(ExternalConstants.EXTRA_TRACK, ContentUris.withAppendedId(GPStracking.Tracks.CONTENT_URI, mTrackId));

            boolean distanceBroadcast = minDistance > 0 && mBroadcastDistance >= minDistance;
            boolean timeBroadcast = minTime > 0 && passedTime >= minTime;
            if (distanceBroadcast && timeBroadcast) {
                Log.d(this, "Broadcasting intent" + intent);
                mBroadcastDistance = 0;
                mLastTimeBroadcast = nowTime;
                mService.sendBroadcast(intent, "android.permission.ACCESS_FINE_LOCATION");
            }
        }
    }

    /**
     * Store a bad location, when to many bad locations are stored the the
     * storage is cleared and the least bad one is returned
     *
     * @param location bad location
     * @return null when the bad location is stored or the least bad one if the
     * storage was full
     */
    private Location addBadLocation(Location location) {
        mWeakLocations.add(location);
        if (mWeakLocations.size() < 3) {
            location = null;
        } else {
            Location best = mWeakLocations.lastElement();
            for (Location whimp : mWeakLocations) {
                if (whimp.hasAccuracy() && best.hasAccuracy() && whimp.getAccuracy() < best.getAccuracy()) {
                    best = whimp;
                } else {
                    if (whimp.hasAccuracy() && !best.hasAccuracy()) {
                        best = whimp;
                    }
                }
            }
            synchronized (mWeakLocations) {
                mWeakLocations.clear();
            }
            location = best;
        }
        return location;
    }

    void stopListening() {
        mLocationManager.removeUpdates(this);
    }


    /**
     * Builds a bit of knowledge about altitudes to expect and return if the
     * added value is deemed sane.
     *
     * @param altitude
     * @return whether the altitude is considered sane
     */
    private boolean addSaneAltitude(double altitude) {
        boolean sane = true;
        double avg = 0;
        int elements = 0;
        // Even insane altitude shifts increases alter perception
        mAltitudes.add(altitude);
        if (mAltitudes.size() > 3) {
            mAltitudes.poll();
        }
        for (Double alt : mAltitudes) {
            avg += alt;
            elements++;
        }
        avg = avg / elements;
        sane = Math.abs(altitude - avg) < MAX_REASONABLE_ALTITUDECHANGE;

        return sane;
    }

    private void crashProtectState() {
        mPersistence.setTrackId(mTrackId);
        mPersistence.setSegmentId(mSegmentId);
        mPersistence.setLoggingState(mLoggingState);

        Log.d(this, "Save GPS Listen State()");
    }

    private synchronized void crashRestoreState() {
        mPrecision = mPersistence.getPrecision();
        mDistance = mPersistence.getDistance();

        int previousState = mPersistence.getLoggingState();
        if (previousState == ExternalConstants.STATE_LOGGING || previousState == ExternalConstants.STATE_PAUSED) {
            Log.d(this, "Load GPS Listen State()");
            mLoggerNotification.startLogging(mPrecision, mLoggingState, mStatusMonitor, mTrackId);

            mTrackId = mPersistence.getTrackId();
            mSegmentId = mPersistence.getSegmentId();
            if (previousState == ExternalConstants.STATE_LOGGING) {
                mLoggingState = ExternalConstants.STATE_PAUSED;
                GPSLoggerServiceManager.resumeGPSLogging(mService);
            } else if (previousState == ExternalConstants.STATE_PAUSED) {
                mLoggingState = ExternalConstants.STATE_LOGGING;
                pauseLogging();
            }
        }
    }


    public synchronized void startLogging(String trackName) {
        Log.d(this, "startLogging()");
        if (this.mLoggingState == ExternalConstants.STATE_STOPPED) {
            boolean shouldDisplayTrack = (trackName == null);
            Uri trackUri = startNewTrack(shouldDisplayTrack);
            if (trackName != null) {
                updateTrackName(trackUri, trackName);
            }
            sendRequestLocationUpdatesMessage();
            sendRequestStatusUpdateMessage();
            this.mLoggingState = ExternalConstants.STATE_LOGGING;
            mPowerManager.updateWakeLock(getLoggingState());
            mLoggerNotification.startLogging(mPrecision, mLoggingState, mStatusMonitor, mTrackId);
            crashProtectState();
            broadCastLoggingState();
        }
    }

    private void updateTrackName(Uri trackUri, String trackName) {
        ContentValues values = new ContentValues();
        values.put(GPStracking.Tracks.NAME, trackName);
        mService.getContentResolver().update(trackUri, values, null, null);
    }

    public synchronized void pauseLogging() {
        Log.d(this, "pauseLogging()");
        if (this.mLoggingState == ExternalConstants.STATE_LOGGING) {
            mLocationManager.removeGpsStatusListener(this);
            stopListening();
            mLoggingState = ExternalConstants.STATE_PAUSED;
            mPreviousLocation = null;
            mPowerManager.updateWakeLock(getLoggingState());
            mLoggerNotification.updateLogging(mPrecision, mLoggingState, mStatusMonitor, mTrackId);
            mLoggerNotification.numberOfSatellites = 0;
            mLoggerNotification.updateLogging(mPrecision, mLoggingState, mStatusMonitor, mTrackId);
            crashProtectState();
            broadCastLoggingState();
        }
    }

    public synchronized void resumeLogging() {
        Log.d(this, "resumeLogging()");
        if (this.mLoggingState == ExternalConstants.STATE_PAUSED) {
            if (mPrecision != ExternalConstants.LOGGING_GLOBAL) {
                mStartNextSegment = true;
            }
            sendRequestLocationUpdatesMessage();
            sendRequestStatusUpdateMessage();

            this.mLoggingState = ExternalConstants.STATE_LOGGING;
            mPowerManager.updateWakeLock(getLoggingState());
            mLoggerNotification.updateLogging(mPrecision, mLoggingState, mStatusMonitor, mTrackId);
            crashProtectState();
            broadCastLoggingState();
        }
    }

    public synchronized void stopLogging() {
        Log.d(this, "stopLogging()");
        mLoggingState = ExternalConstants.STATE_STOPPED;
        crashProtectState();
        mPowerManager.updateWakeLock(getLoggingState());

        mLocationManager.removeGpsStatusListener(this);
        stopListening();
        mLoggerNotification.stopLogging();

        broadCastLoggingState();
    }

    public Uri storeMetaData(String key, String value) {
        Uri uri = Uri.withAppendedPath(GPStracking.Tracks.CONTENT_URI, mTrackId + "/metadata");

        if (mTrackId >= 0) {
            Cursor cursor = null;
            try {
                cursor = mService.getContentResolver().query(
                        uri, new String[]{GPStracking.MetaData.VALUE},
                        GPStracking.MetaData.KEY + " = ? ", new String[]{key}, null);
                if (cursor.moveToFirst()) {
                    ContentValues args = new ContentValues();
                    args.put(GPStracking.MetaData.VALUE, value);
                    mService.getContentResolver().update(
                            uri, args,
                            GPStracking.MetaData.KEY + " = ? ", new String[]{key});
                } else {
                    ContentValues args = new ContentValues();
                    args.put(GPStracking.MetaData.KEY, key);
                    args.put(GPStracking.MetaData.VALUE, value);
                    mService.getContentResolver().insert(uri, args);
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }

        return uri;
    }

    /**
     * Send a system broadcast to notify a change in the logging or precision
     */
    private void broadCastLoggingState() {
        Intent broadcast = new Intent(ExternalConstants.LOGGING_STATE_CHANGED_ACTION);
        broadcast.putExtra(ExternalConstants.EXTRA_LOGGING_PRECISION, mPrecision);
        broadcast.putExtra(ExternalConstants.EXTRA_LOGGING_STATE, mLoggingState);
        mService.getApplicationContext().sendBroadcast(broadcast);
    }

    /**
     * @param provider
     * @param milliseconds
     * @param meters
     * @param accuracy
     */
    private void startListening(String provider, long milliseconds, float meters, float accuracy) {
        Log.d(this, "startListening(" + provider + ", " + milliseconds / 1000L + "s, " + meters + "m, " + accuracy + "m)");
        mProvider = provider;
        mMaxAcceptableAccuracy = accuracy;
        mLocationManager.removeUpdates(this);
        mLocationManager.requestLocationUpdates(provider, milliseconds, meters, this);
        mCheckPeriod = Math.max(12 * milliseconds, 120);
    }

    void verifyLoggingState() {
        if (mLoggingState == ExternalConstants.STATE_LOGGING) {
            // Collect the last location from the last logged location or a more recent from the last weak location
            Location checkLocation = mPreviousLocation;
            synchronized (mWeakLocations) {
                if (!mWeakLocations.isEmpty()) {
                    if (checkLocation == null) {
                        checkLocation = mWeakLocations.lastElement();
                    } else {
                        Location weakLocation = mWeakLocations.lastElement();
                        checkLocation = weakLocation.getTime() > checkLocation.getTime() ? weakLocation : checkLocation;
                    }
                }
            }
            // Is the last known GPS location something nearby we are not told?
            Location managerLocation = mLocationManager.getLastKnownLocation(mProvider);
            if (managerLocation != null && checkLocation != null) {
                if (checkLocation.distanceTo(managerLocation) < 2 * mMaxAcceptableAccuracy) {
                    checkLocation = managerLocation.getTime() > checkLocation.getTime() ? managerLocation : checkLocation;
                }
            }

            if (checkLocation == null || checkLocation.getTime() + mCheckPeriod < new Date().getTime()) {
                Log.w(this, "GPS system failed to produce a location during logging: " + checkLocation);
                mLoggerNotification.startPoorSignal(mTrackId);
                if (mStatusMonitor) {
                    mLoggerNotification.soundGpsSignalAlarm();
                }

                mLoggingState = ExternalConstants.STATE_PAUSED;
                GPSLoggerServiceManager.resumeGPSLogging(GPSListener.this.mService);
            } else {
                mLoggerNotification.stopPoorSignal();
            }
        }
    }

    public long getCheckPeriod() {
        return mCheckPeriod;
    }

    /**
     * Trigged by events that start a new track
     */
    private Uri startNewTrack(boolean shouldDisplayTrack) {
        mDistance = 0;
        Uri newTrack = mService.getContentResolver().insert(GPStracking.Tracks.CONTENT_URI, new ContentValues(0));
        mTrackId = Long.valueOf(newTrack.getLastPathSegment()).longValue();
        startNewSegment();
        if (shouldDisplayTrack) {
            Intent intent = new Intent(Intent.ACTION_VIEW, newTrack);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            mService.startActivity(intent);
        }

        return newTrack;
    }

    protected void storeMediaUri(Uri mediaUri) {
        if (isMediaPrepared()) {
            Uri mediaInsertUri = Uri.withAppendedPath(GPStracking.Tracks.CONTENT_URI, mTrackId + "/segments/" + mSegmentId +
                    "/waypoints/" + mWaypointId + "/media");
            ContentValues args = new ContentValues();
            args.put(GPStracking.Media.URI, mediaUri.toString());
            mService.getContentResolver().insert(mediaInsertUri, args);
        } else {
            Log.e(this, "No logging done under which to store the track");
        }
    }

    protected boolean isMediaPrepared() {
        return !(mTrackId < 0 || mSegmentId < 0 || mWaypointId < 0);
    }

    private void sendRequestStatusUpdateMessage() {
        mLocationManager.addGpsStatusListener(this);
    }

    public void removeGpsStatusListener() {
        mLocationManager.removeGpsStatusListener(this);
    }

    public long getTrackId() {
        return mTrackId;
    }

    public int getLoggingState() {
        return mLoggingState;
    }

    /**
     * Provides the cached last stored waypoint it current logging is active alse
     * null.
     *
     * @return last waypoint location or null
     */
    Location getLastWaypoint() {
        Location myLastWaypoint = null;
        if (isLogging()) {
            myLastWaypoint = mPreviousLocation;
        }
        return myLastWaypoint;
    }

    float getTrackedDistance() {
        float distance = 0F;
        if (isLogging()) {
            distance = mDistance;
        }
        return distance;
    }
}
