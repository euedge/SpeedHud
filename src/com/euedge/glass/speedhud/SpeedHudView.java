/*
 * Copyright (C) 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.euedge.glass.speedhud;

import java.io.File;
import java.text.DecimalFormat;
import java.text.NumberFormat;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import com.euedge.glass.speedhud.util.MathUtils;

/**
 * Draws a stylized compass, with text labels at the cardinal and ordinal directions, and tick
 * marks at the half-winds. The red "needles" in the display mark the current heading.
 */
public class SpeedHudView extends View {
    
    public static final int UOM_KMH = 0;
    public static final int UOM_MPH = 1;
    public static final int UOM_KT = 2;
    public static final int UOM_MPS = 3;
    public static final int UOM_DEFAULT = UOM_MPH;
    
    private static final double KMH_IN_MPS = 0.277777778;
    private static final double MPH_IN_MPS = 0.44704;
    private static final double KT_IN_MPS = 0.514444444;
    

    /** Various dimensions and other drawing-related constants. */
    private static final float NEEDLE_WIDTH = 1;
    private static final float NEEDLE_HEIGHT = 44;
    private static final int NEEDLE_COLOR = 0xffcc3333;
    private static final int DIRECTION_TEXT_COLOR = 0xffffffff;
    private static final int SPEED_COLOR = 0xff99cc33;
    private static final int SPEED_UOM_COLOR = 0xffffffff;
    private static final float TICK_WIDTH = 2;
    private static final float TICK_LONG_HEIGHT = 40;
    private static final float TICK_SHORT_HEIGHT = 20;
    private static final int TICK_LONG_COLOR = 0xffffffff;
    private static final int TICK_SHORT_COLOR = 0xff808080;
    private static final float DIRECTION_TEXT_HEIGHT = 36.0f;
    private static final float SPEED_TEXT_HEIGHT = 256.0f;
    private static final float UOM_TEXT_HEIGHT = 72.0f;

    /**
     * If the difference between two consecutive headings is less than this value, the canvas will
     * be redrawn immediately rather than animated.
     */
    private static final float MIN_DISTANCE_TO_ANIMATE = 15.0f;

    /** The actual heading that represents the direction that the user is facing. */
    private float mHeading;

    /**
     * Represents the heading that is currently being displayed when the view is drawn. This is
     * used during animations, to keep track of the heading that should be drawn on the current
     * frame, which may be different than the desired end point.
     */
    private float mAnimatedHeading;

    private OrientationManager mOrientation;

    private final Paint mPaint;
    private final Paint mTickPaint;
    private final Path mPath;
    private final Typeface mCompassTypeface;
    private final Typeface mSpeedTypeface;
    private final Rect mTextBounds;
    private final NumberFormat mDistanceFormat;
    private final String[] mDirections;
    private final ValueAnimator mAnimator;
    private int uom = UOM_DEFAULT;

    public SpeedHudView(Context context) {
        this(context, null, 0);
    }

    public SpeedHudView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SpeedHudView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        mPaint = new Paint();
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setAntiAlias(true);

        mCompassTypeface = Typeface.createFromFile(new File("/system/glass_fonts",
                                                    "Roboto-Thin.ttf"));
        mSpeedTypeface = Typeface.createFromFile(new File("/system/glass_fonts",
                                                    "Roboto-Thin.ttf"));

        
        mTickPaint = new Paint();
        mTickPaint.setStyle(Paint.Style.STROKE);
        mTickPaint.setStrokeWidth(TICK_WIDTH);
        mTickPaint.setAntiAlias(true);
        mTickPaint.setColor(Color.WHITE);

        mPath = new Path();
        mTextBounds = new Rect();

        mDistanceFormat = NumberFormat.getNumberInstance();
        mDistanceFormat.setMinimumFractionDigits(0);
        mDistanceFormat.setMaximumFractionDigits(1);

        // We use NaN to indicate that the compass is being drawn for the first
        // time, so that we can jump directly to the starting orientation
        // instead of spinning from a default value of 0.
        mAnimatedHeading = Float.NaN;

        mDirections = context.getResources().getStringArray(R.array.direction_abbreviations);

        mAnimator = new ValueAnimator();
        setupAnimator();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        setVisibility(VISIBLE);
    }


    /**
     * Sets the instance of {@link OrientationManager} that this view will use to get the current
     * heading and location.
     *
     * @param orientationManager the instance of {@code OrientationManager} that this view will use
     */
    public void setOrientationManager(OrientationManager orientationManager) {
        mOrientation = orientationManager;
    }

    /**
     * Gets the current heading in degrees.
     *
     * @return the current heading.
     */
    public float getHeading() {
        return mHeading;
    }

    /**
     * Sets the current heading in degrees and redraws the compass. If the angle is not between 0
     * and 360, it is shifted to be in that range.
     *
     * @param degrees the current heading
     */
    public void setHeading(float degrees) {
        mHeading = MathUtils.mod(degrees, 360.0f);
        animateTo(mHeading);
    }

    /**
     * Sets the current speed in m/s and redraws the HUD.
     *
     * @param degrees the current heading
     */
    public void setSpeed(float speed) {
        invalidate();
    }
    
    /**
     * Set the unit of measurement.
     * 
     * @param uom the new  unit of measurement.
     */
    public void setUom(int uom) {
        switch (uom) {
        case UOM_KMH: this.uom = UOM_KMH; break;
        case UOM_MPH: this.uom = UOM_MPH; break;
        case UOM_KT:  this.uom = UOM_KT; break;
        case UOM_MPS: this.uom = UOM_MPS; break;
        default:      this.uom = UOM_DEFAULT;
        }
        
        invalidate();
    }
    
    public int getUom() {
        return uom;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // The view displays 90 degrees across its width so that one 90 degree head rotation is
        // equal to one full view cycle.
        float pixelsPerDegree = getWidth() / 90.0f;
        float centerX = getWidth() / 2.0f;

        canvas.save();
        canvas.translate(-mAnimatedHeading * pixelsPerDegree + centerX, 0);

        // draw the compass
        drawCompassDirections(canvas, pixelsPerDegree);
        
        canvas.restore();
        
        drawNeedle(canvas);
        
        // now the speed
        drawSpeed(canvas);
    }

    /**
     * Draws the compass direction strings (N, NW, W, etc.).
     *
     * @param canvas the {@link Canvas} upon which to draw
     * @param pixelsPerDegree the size, in pixels, of one degree step
     */
    private void drawCompassDirections(Canvas canvas, float pixelsPerDegree) {
        float degreesPerTick = 360.0f / mDirections.length;

        mPaint.setStrokeWidth(TICK_WIDTH);
        mPaint.setColor(DIRECTION_TEXT_COLOR);
        mPaint.setTextSize(DIRECTION_TEXT_HEIGHT);
        mPaint.setTypeface(mCompassTypeface);

        // We draw two extra ticks/labels on each side of the view so that the
        // full range is visible even when the heading is approximately 0.
        for (int i = -2; i <= mDirections.length + 2; i++) {
            if (MathUtils.mod(i, 2) == 0) {
                // Draw a text label for the even indices.
                String direction = mDirections[MathUtils.mod(i, mDirections.length)];
                mPaint.getTextBounds(direction, 0, direction.length(), mTextBounds);

                canvas.drawText(direction,
                        i * degreesPerTick * pixelsPerDegree - mTextBounds.width() / 2,
                        70 + mTextBounds.height() / 2, mPaint);
            }
        }
        
        // overdraw
        for (int i = -180; i <= 540; i += 15) {
            if ((i % 45) == 0) {
                mPaint.setColor(TICK_LONG_COLOR);
                canvas.drawLine(i * pixelsPerDegree, 0,
                                i * pixelsPerDegree, TICK_LONG_HEIGHT,
                                mTickPaint);
            } else {
                mPaint.setColor(TICK_SHORT_COLOR);
                canvas.drawLine(i * pixelsPerDegree, 10,
                        i * pixelsPerDegree, 10 + TICK_SHORT_HEIGHT,
                        mTickPaint);
            }
        }
    }

    /**
     * Draws the speed
     *
     * @param canvas the {@link Canvas} upon which to draw
     */
    private void drawSpeed(Canvas canvas) {
        mPaint.setTypeface(mSpeedTypeface);
        
        int speed = (int) mOrientation.getLocation().getSpeed();
        String uomStr;
        switch (uom) {
        case UOM_KMH:
            speed /= KMH_IN_MPS;
            uomStr = " km/h";
            break;
        case UOM_MPH:
            speed /= MPH_IN_MPS;
            uomStr = " mph";
            break;
        case UOM_KT:
            speed /= KT_IN_MPS;
            uomStr = " kt";
            break;
        case UOM_MPS:
        default:
            uomStr = " m/s";
        }
        if (speed > 1000) {
            speed = 999;
        }
        
        final DecimalFormat smallNumberFormat = new DecimalFormat("0.0");
        
        String speedStr = speed < 10 ? smallNumberFormat.format(speed)
                        : Integer.toString(speed);
        String testStr = "000";
        mPaint.setTextSize(SPEED_TEXT_HEIGHT);
        mPaint.getTextBounds(testStr, 0, testStr.length(), mTextBounds);
        mPaint.setTextSize(UOM_TEXT_HEIGHT);
        mPaint.getTextBounds(uomStr, 0, uomStr.length(), mTextBounds);
        
        Paint.Align oldAlign = mPaint.getTextAlign();
        
        mPaint.setTextAlign(Paint.Align.RIGHT);
        mPaint.setTextSize(SPEED_TEXT_HEIGHT);
        mPaint.setColor(SPEED_COLOR);
        canvas.drawText(speedStr, getWidth() - 218, getHeight() - 40, mPaint);
        
        mPaint.setTextAlign(Paint.Align.LEFT);
        mPaint.setTextSize(UOM_TEXT_HEIGHT);
        mPaint.setColor(SPEED_UOM_COLOR);
        canvas.drawText(uomStr, 442, getHeight() - 40, mPaint);
        
        mPaint.setTextAlign(oldAlign);
    }

    
    /**
     * Draws a needle that highlights the center of the compass.
     *
     * @param canvas the {@link Canvas} upon which to draw
     */
    private void drawNeedle(Canvas canvas) {
        mPaint.setColor(NEEDLE_COLOR);
        mPaint.setStrokeWidth(NEEDLE_WIDTH);

        canvas.drawLine(313, 0, 313, NEEDLE_HEIGHT, mPaint);
        canvas.drawLine(313, NEEDLE_HEIGHT, 325, NEEDLE_HEIGHT, mPaint);
        canvas.drawLine(325, 0, 325, NEEDLE_HEIGHT, mPaint);
    }

    /**
     * Sets up a {@link ValueAnimator} that will be used to animate the compass
     * when the distance between two sensor events is large.
     */
    private void setupAnimator() {
        mAnimator.setInterpolator(new LinearInterpolator());
        mAnimator.setDuration(250);

        // Notifies us at each frame of the animation so we can redraw the view.
        mAnimator.addUpdateListener(new AnimatorUpdateListener() {

            @Override
            public void onAnimationUpdate(ValueAnimator animator) {
                mAnimatedHeading = MathUtils.mod((Float) mAnimator.getAnimatedValue(), 360.0f);
                invalidate();
            }
        });

        // Notifies us when the animation is over. During an animation, the user's head may have
        // continued to move to a different orientation than the original destination angle of the
        // animation. Since we can't easily change the animation goal while it is running, we call
        // animateTo() again, which will either redraw at the new orientation (if the difference is
        // small enough), or start another animation to the new heading. This seems to produce
        // fluid results.
        mAnimator.addListener(new AnimatorListenerAdapter() {

            @Override
            public void onAnimationEnd(Animator animator) {
                animateTo(mHeading);
            }
        });
    }

    /**
     * Animates the view to the specified heading, or simply redraws it immediately if the
     * difference between the current heading and new heading are small enough that it wouldn't be
     * noticeable.
     *
     * @param end the desired heading
     */
    private void animateTo(float end) {
        // Only act if the animator is not currently running. If the user's orientation changes
        // while the animator is running, we wait until the end of the animation to update the
        // display again, to prevent jerkiness.
        if (!mAnimator.isRunning()) {
            float start = mAnimatedHeading;
            float distance = Math.abs(end - start);
            float reverseDistance = 360.0f - distance;
            float shortest = Math.min(distance, reverseDistance);

            if (Float.isNaN(mAnimatedHeading) || shortest < MIN_DISTANCE_TO_ANIMATE) {
                // If the distance to the destination angle is small enough (or if this is the
                // first time the compass is being displayed), it will be more fluid to just redraw
                // immediately instead of doing an animation.
                mAnimatedHeading = end;
                invalidate();
            } else {
                // For larger distances (i.e., if the compass "jumps" because of sensor calibration
                // issues), we animate the effect to provide a more fluid user experience. The
                // calculation below finds the shortest distance between the two angles, which may
                // involve crossing 0/360 degrees.
                float goal;

                if (distance < reverseDistance) {
                    goal = end;
                } else if (end < start) {
                    goal = end + 360.0f;
                } else {
                    goal = end - 360.0f;
                }

                mAnimator.setFloatValues(start, goal);
                mAnimator.start();
            }
        }
    }
}
