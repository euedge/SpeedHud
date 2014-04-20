/*
 * Copyright (C) 2013 EU Edge LLC
 *
 * This code is modification of a work of The Android Open Source Project,
 * see the original license statement below.
 *
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.euedge.glass.speedhud;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.hardware.SensorManager;
import android.location.LocationManager;
import android.os.Binder;
import android.os.IBinder;
import android.speech.tts.TextToSpeech;

import com.euedge.glass.speedhud.util.MathUtils;
import com.google.android.glass.timeline.LiveCard;
import com.google.android.glass.timeline.LiveCard.PublishMode;

/**
 * The main application service that manages the lifetime of the speed HUD live card.
 */
public class SpeedHudService extends Service {

    private static final String LIVE_CARD_TAG = "speed_hud";
    
    private static final String PREFERENCES_NAME = SpeedHudService.class.toString();
    private static final String PREFS_UOM_KEY = "key_uom";

    /**
     * A binder that gives other components access to the speech capabilities provided by the
     * service.
     */
    public class SpeedHudBinder extends Binder {
        /**
         * Read the current heading aloud using the text-to-speech engine.
         */
        public void readHeadingAloud() {
            float heading = mOrientationManager.getHeading();

            Resources res = getResources();
            String[] spokenDirections = res.getStringArray(R.array.spoken_directions);
            String directionName = spokenDirections[MathUtils.getHalfWindIndex(heading)];

            int roundedHeading = Math.round(heading);
            int headingFormat;
            if (roundedHeading == 1) {
                headingFormat = R.string.spoken_heading_format_one;
            } else {
                headingFormat = R.string.spoken_heading_format;
            }

            String headingText = res.getString(headingFormat, roundedHeading, directionName);
            mSpeech.speak(headingText, TextToSpeech.QUEUE_FLUSH, null);
        }
        
        public SpeedHudService getSpeedHudService() {
            return SpeedHudService.this;
        }
    }

    private final SpeedHudBinder mBinder = new SpeedHudBinder();

    private OrientationManager mOrientationManager;
    private TextToSpeech mSpeech;

    private LiveCard mLiveCard;
    private SpeedHudRenderer mRenderer;
    
    @Override
    public void onCreate() {
        super.onCreate();

        // Even though the text-to-speech engine is only used in response to a menu action, we
        // initialize it when the application starts so that we avoid delays that could occur
        // if we waited until it was needed to start it up.
        mSpeech = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                // Do nothing.
            }
        });

        SensorManager sensorManager =
                (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        LocationManager locationManager =
                (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        mOrientationManager = new OrientationManager(sensorManager, locationManager);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (mLiveCard == null) {
            mLiveCard = new LiveCard(this, LIVE_CARD_TAG);
            mRenderer = new SpeedHudRenderer(this, mOrientationManager);

            LiveCard direct = mLiveCard.setDirectRenderingEnabled(true);
            direct.getSurfaceHolder().addCallback(mRenderer);
            direct.getSurfaceHolder().setKeepScreenOn(true);

            // Display the options menu when the live card is tapped.
            Intent menuIntent = new Intent(this, SpeedHudMenuActivity.class);
            menuIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            mLiveCard.setAction(PendingIntent.getActivity(this, 0, menuIntent, 0));
            mLiveCard.attach(this);
            mLiveCard.publish(PublishMode.REVEAL);
        }
        
        SharedPreferences prefs =
                        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE);
        int uom = prefs.getInt(PREFS_UOM_KEY, SpeedHudView.UOM_DEFAULT);
        mRenderer.setUom(uom);

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        SharedPreferences prefs =
                        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE);
        final SharedPreferences.Editor edit = prefs.edit();
        edit.putInt(PREFS_UOM_KEY, mRenderer.getUom());
        edit.commit();

        if (mLiveCard != null && mLiveCard.isPublished()) {
            mLiveCard.unpublish();
            mLiveCard = null;
        }

        mSpeech.shutdown();

        mSpeech = null;
        mOrientationManager = null;

        super.onDestroy();
    }
    
    public void setUom(int uom) {
        mRenderer.setUom(uom);
    }
}
