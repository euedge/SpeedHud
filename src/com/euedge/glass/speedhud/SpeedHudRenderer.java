/*
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

import java.util.concurrent.TimeUnit;

import android.content.Context;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

/**
 * The surface callback that provides the rendering logic for the speed HUD live card. This callback
 * also manages the lifetime of the sensor and location event listeners (through
 * {@link OrientationManager}) so that tracking only occurs when the card is visible.
 */
public class SpeedHudRenderer implements SurfaceHolder.Callback {

    private static final String TAG = SpeedHudRenderer.class.getSimpleName();

    /**
     * The (absolute) pitch angle beyond which the compass will display a message telling the user
     * that his or her head is at too steep an angle to be reliable.
     */
    private static final float TOO_STEEP_PITCH_DEGREES = 70.0f;

    /** The refresh rate, in frames per second, of the compass. */
    private static final int REFRESH_RATE_FPS = 45;

    /** The duration, in milliseconds, of one frame. */
    private static final long FRAME_TIME_MILLIS = TimeUnit.SECONDS.toMillis(1) / REFRESH_RATE_FPS;

    private SurfaceHolder mHolder;
    private boolean mTooSteep;
    private boolean mInterference;
    private RenderThread mRenderThread;
    private int mSurfaceWidth;
    private int mSurfaceHeight;

    private final FrameLayout mLayout;
    private final SpeedHudView mSpeedHudView;
    private final RelativeLayout mTipsContainer;
    private final TextView mTipsView;
    private final OrientationManager mOrientationManager;

    private final OrientationManager.OnChangedListener mSpeedHudListener =
            new OrientationManager.OnChangedListener() {

        @Override
        public void onOrientationChanged(OrientationManager orientationManager) {
            mSpeedHudView.setHeading(orientationManager.getHeading());

            boolean oldTooSteep = mTooSteep;
            mTooSteep = (Math.abs(orientationManager.getPitch()) > TOO_STEEP_PITCH_DEGREES);
            if (mTooSteep != oldTooSteep) {
                updateTipsView();
            }
        }

        @Override
        public void onLocationChanged(OrientationManager orientationManager) {
            mSpeedHudView.setSpeed(orientationManager.getLocation().getSpeed());
        }

        @Override
        public void onAccuracyChanged(OrientationManager orientationManager) {
            mInterference = orientationManager.hasInterference();
            updateTipsView();
        }
    };

    /**
     * Creates a new instance of the {@code SpeedHudRenderer} with the specified context
     * and orientation manager.
     */
    public SpeedHudRenderer(Context context, OrientationManager orientationManager) {
        LayoutInflater inflater = LayoutInflater.from(context);
        mLayout = (FrameLayout) inflater.inflate(R.layout.speed_hud, null);
        mLayout.setWillNotDraw(false);

        mSpeedHudView = (SpeedHudView) mLayout.findViewById(R.id.speed_hud);
        mTipsContainer = (RelativeLayout) mLayout.findViewById(R.id.tips_container);
        mTipsView = (TextView) mLayout.findViewById(R.id.tips_view);

        mOrientationManager = orientationManager;

        mSpeedHudView.setOrientationManager(mOrientationManager);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        mSurfaceWidth = width;
        mSurfaceHeight = height;
        doLayout();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mHolder = holder;

        mOrientationManager.addOnChangedListener(mSpeedHudListener);
        mOrientationManager.start();

        mRenderThread = new RenderThread();
        mRenderThread.start();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mRenderThread.quit();

        mOrientationManager.removeOnChangedListener(mSpeedHudListener);
        mOrientationManager.stop();
    }

    /**
     * Requests that the views redo their layout. This must be called manually every time the
     * tips view's text is updated because this layout doesn't exist in a GUI thread where those
     * requests will be enqueued automatically.
     */
    private void doLayout() {
        // Measure and update the layout so that it will take up the entire surface space
        // when it is drawn.
        int measuredWidth = View.MeasureSpec.makeMeasureSpec(mSurfaceWidth,
                View.MeasureSpec.EXACTLY);
        int measuredHeight = View.MeasureSpec.makeMeasureSpec(mSurfaceHeight,
                View.MeasureSpec.EXACTLY);

        mLayout.measure(measuredWidth, measuredHeight);
        mLayout.layout(0, 0, mLayout.getMeasuredWidth(), mLayout.getMeasuredHeight());
    }

    /**
     * Repaints the speed HUD.
     */
    private synchronized void repaint() {
        Canvas canvas = null;

        try {
            canvas = mHolder.lockCanvas();
        } catch (RuntimeException e) {
            Log.d(TAG, "lockCanvas failed", e);
        }

        if (canvas != null) {
            mLayout.draw(canvas);

            try {
                mHolder.unlockCanvasAndPost(canvas);
            } catch (RuntimeException e) {
                Log.d(TAG, "unlockCanvasAndPost failed", e);
            }
        }
    }

    /**
     * Shows or hides the tip view with an appropriate message based on the current accuracy of the
     * sensors.
     */
    private void updateTipsView() {
        int stringId = 0;

        // Only one message (with magnetic interference being higher priority than pitch too steep)
        // will be displayed in the tip.
        if (mInterference) {
            stringId = R.string.magnetic_interference;
        } else if (mTooSteep) {
            stringId = R.string.pitch_too_steep;
        }

        boolean show = (stringId != 0);

        if (show) {
            mTipsView.setText(stringId);
            doLayout();
        }

        if (mTipsContainer.getAnimation() == null) {
            float newAlpha = (show ? 1.0f : 0.0f);
            mTipsContainer.animate().alpha(newAlpha).start();
        }
    }
    
    public void setUom(int uom) {
        mSpeedHudView.setUom(uom);
    }

    public int getUom() {
        return mSpeedHudView.getUom();
    }

    /**
     * Redraws the s[eed HUD in the background.
     */
    private class RenderThread extends Thread {
        private boolean mShouldRun;

        /**
         * Initializes the background rendering thread.
         */
        public RenderThread() {
            mShouldRun = true;
        }

        /**
         * Returns true if the rendering thread should continue to run.
         *
         * @return true if the rendering thread should continue to run
         */
        private synchronized boolean shouldRun() {
            return mShouldRun;
        }

        /**
         * Requests that the rendering thread exit at the next opportunity.
         */
        public synchronized void quit() {
            mShouldRun = false;
        }

        @Override
        public void run() {
            while (shouldRun()) {
                long frameStart = SystemClock.elapsedRealtime();
                repaint();
                long frameLength = SystemClock.elapsedRealtime() - frameStart;

                long sleepTime = FRAME_TIME_MILLIS - frameLength;
                if (sleepTime > 0) {
                    SystemClock.sleep(sleepTime);
                }
            }
        }
    }
}
