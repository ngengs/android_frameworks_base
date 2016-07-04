/*
 * Copyright (c) 2013-2014, The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.keyguard;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import android.os.UserHandle;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.util.TypedValue;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.TextClock;
import android.widget.TextView;

import com.android.internal.util.du.WeatherController;
import com.android.internal.util.du.WeatherControllerImpl;
import com.android.internal.util.du.ImageHelper;
import com.android.internal.widget.LockPatternUtils;

import java.util.Date;
import java.util.Locale;

public class KeyguardStatusView extends GridLayout implements
        WeatherController.Callback {
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final String TAG = "KeyguardStatusView";

    private final LockPatternUtils mLockPatternUtils;
    private final AlarmManager mAlarmManager;

    private TextView mAlarmStatusView;
    private TextClock mDateView;
    private TextClock mClockView;
    private TextView mOwnerInfo;
    private View mWeatherView;
    private TextView mWeatherCity;
    private ImageView mWeatherConditionImage;
    private Drawable mWeatherConditionDrawable;
    private TextView mWeatherCurrentTemp;
    private TextView mWeatherConditionText;
    private boolean mShowWeather;
    private int mIconNameValue = 0;
    private int mPrimaryTextColor;
    private int mIconColor;
    private WeatherController mWeatherController;

    //On the first boot, keygard will start to receiver TIME_TICK intent.
    //And onScreenTurnedOff will not get called if power off when keyguard is not started.
    //Set initial value to false to skip the above case.
    private boolean mEnableRefresh = false;

    private KeyguardUpdateMonitorCallback mInfoCallback = new KeyguardUpdateMonitorCallback() {

        @Override
        public void onTimeChanged() {
            if (mEnableRefresh) {
                refresh();
            }
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            if (showing) {
                if (DEBUG) Slog.v(TAG, "refresh statusview showing:" + showing);
                refresh();
                updateOwnerInfo();
            }
        }

        @Override
        public void onStartedWakingUp() {
            setEnableMarquee(true);
            mEnableRefresh = true;
            refresh();
        }

        @Override
        public void onFinishedGoingToSleep(int why) {
            setEnableMarquee(false);
            mEnableRefresh = false;
        }

        @Override
        public void onUserSwitchComplete(int userId) {
            refresh();
            updateOwnerInfo();
        }
    };

    public KeyguardStatusView(Context context) {
        this(context, null, 0);
    }

    public KeyguardStatusView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardStatusView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mWeatherController = new WeatherControllerImpl(mContext);
        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        mLockPatternUtils = new LockPatternUtils(getContext());
    }

    private void setEnableMarquee(boolean enabled) {
        if (DEBUG) Log.v(TAG, (enabled ? "Enable" : "Disable") + " transport text marquee");
        if (mAlarmStatusView != null) mAlarmStatusView.setSelected(enabled);
        if (mOwnerInfo != null) mOwnerInfo.setSelected(enabled);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mAlarmStatusView = (TextView) findViewById(R.id.alarm_status);
        mDateView = (TextClock) findViewById(R.id.date_view);
        mClockView = (TextClock) findViewById(R.id.clock_view);
        mDateView.setShowCurrentUserTime(true);
        mClockView.setShowCurrentUserTime(true);
        mOwnerInfo = (TextView) findViewById(R.id.owner_info);
        mWeatherView = findViewById(R.id.keyguard_weather_view);
        mWeatherCity = (TextView) findViewById(R.id.city);
        mWeatherConditionImage = (ImageView) findViewById(R.id.weather_image);
        mWeatherCurrentTemp = (TextView) findViewById(R.id.current_temp);
        mWeatherConditionText = (TextView) findViewById(R.id.condition);

        boolean shouldMarquee = KeyguardUpdateMonitor.getInstance(mContext).isDeviceInteractive();
        setEnableMarquee(shouldMarquee);
        refresh();
        updateOwnerInfo();

        // Disable elegant text height because our fancy colon makes the ymin value huge for no
        // reason.
        mClockView.setElegantTextHeight(false);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_big_font_size));
        mClockView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        mDateView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_label_font_size));
        mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_label_font_size));
    }

    public void refreshTime() {
        mDateView.setFormat24Hour(Patterns.dateView);
        mDateView.setFormat12Hour(Patterns.dateView);

        mClockView.setFormat12Hour(Patterns.clockView12);
        mClockView.setFormat24Hour(Patterns.clockView24);
    }

    private void refresh() {
        AlarmManager.AlarmClockInfo nextAlarm =
                mAlarmManager.getNextAlarmClock(UserHandle.USER_CURRENT);
        Patterns.update(mContext, nextAlarm != null);

        refreshTime();
        refreshAlarmStatus(nextAlarm);
        updateSettings(false);
    }

    void refreshAlarmStatus(AlarmManager.AlarmClockInfo nextAlarm) {
        if (nextAlarm != null) {
            String alarm = formatNextAlarm(mContext, nextAlarm);
            mAlarmStatusView.setText(alarm);
            mAlarmStatusView.setContentDescription(
                    getResources().getString(R.string.keyguard_accessibility_next_alarm, alarm));
            mAlarmStatusView.setVisibility(View.VISIBLE);
        } else {
            mAlarmStatusView.setVisibility(View.GONE);
        }
    }

    public static String formatNextAlarm(Context context, AlarmManager.AlarmClockInfo info) {
        if (info == null) {
            return "";
        }
        String skeleton = DateFormat.is24HourFormat(context, ActivityManager.getCurrentUser())
                ? "EHm"
                : "Ehma";
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton);
        return DateFormat.format(pattern, info.getTriggerTime()).toString();
    }

    private void updateOwnerInfo() {
        if (mOwnerInfo == null) return;
        String ownerInfo = getOwnerInfo();
        if (!TextUtils.isEmpty(ownerInfo)) {
            mOwnerInfo.setVisibility(View.VISIBLE);
            mOwnerInfo.setText(ownerInfo);
        } else {
            mOwnerInfo.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mInfoCallback);
        updateSettings(false);
        mWeatherController.addCallback(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        KeyguardUpdateMonitor.getInstance(mContext).removeCallback(mInfoCallback);
        mWeatherController.removeCallback(this);
    }

    private String getOwnerInfo() {
        ContentResolver res = getContext().getContentResolver();
        String info = null;
        final boolean ownerInfoEnabled = mLockPatternUtils.isOwnerInfoEnabled(
                KeyguardUpdateMonitor.getCurrentUser());
        if (ownerInfoEnabled) {
            info = mLockPatternUtils.getOwnerInfo(KeyguardUpdateMonitor.getCurrentUser());
        }
        return info;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    public void onWeatherChanged(WeatherController.WeatherInfo info) {
        if (info.temp == null || info.condition == null) {
            mWeatherCity.setText("--");
            mWeatherConditionDrawable = null;
            mWeatherCurrentTemp.setText(null);
            mWeatherConditionText.setText(null);
            mWeatherView.setVisibility(View.GONE);
            updateSettings(true);
        } else {
            mWeatherConditionDrawable = info.conditionDrawable;

            if (mWeatherCity != null) {
                mWeatherCity.setText(info.city);
            }
            if (mWeatherCurrentTemp != null) {
                mWeatherCurrentTemp.setText(info.temp);
            }
            if (mWeatherConditionText != null) {
                mWeatherConditionText.setText(info.condition);
            }
            if (mWeatherView != null) {
                mWeatherView.setVisibility(mShowWeather ? View.VISIBLE : View.GONE);
            }
            updateSettings(false);
        }
    }

    private void updateSettings(boolean forceHide) {
        final ContentResolver resolver = getContext().getContentResolver();
        final Resources res = getContext().getResources();
        AlarmManager.AlarmClockInfo nextAlarm =
                mAlarmManager.getNextAlarmClock(UserHandle.USER_CURRENT);

        mShowWeather = Settings.System.getInt(resolver,
                Settings.System.LOCK_SCREEN_SHOW_WEATHER, 0) == 1;
        mIconColor = Settings.System.getInt(resolver,
                Settings.System.LOCK_SCREEN_WEATHER_ICON_COLOR, -2);
        mPrimaryTextColor = Settings.System.getInt(resolver,
                Settings.System.LOCK_SCREEN_WEATHER_TEXT_COLOR, -2);
        boolean showAlarm = Settings.System.getIntForUser(resolver,
                Settings.System.HIDE_LOCKSCREEN_ALARM, 1, UserHandle.USER_CURRENT) == 1;
        boolean showClock = Settings.System.getIntForUser(resolver,
                Settings.System.HIDE_LOCKSCREEN_CLOCK, 1, UserHandle.USER_CURRENT) == 1;
        boolean showDate = Settings.System.getIntForUser(resolver,
                Settings.System.HIDE_LOCKSCREEN_DATE, 1, UserHandle.USER_CURRENT) == 1;
        boolean showLocation = Settings.System.getInt(resolver,
                    Settings.System.LOCK_SCREEN_SHOW_WEATHER_LOCATION, 1) == 1;
        int clockColor = Settings.System.getInt(resolver,
                Settings.System.LOCKSCREEN_CLOCK_COLOR, 0xFFFFFFFF);
        int clockDateColor = Settings.System.getInt(resolver,
                Settings.System.LOCKSCREEN_CLOCK_DATE_COLOR, 0xFFFFFFFF);
        int ownerInfoColor = Settings.System.getInt(resolver,
                Settings.System.LOCKSCREEN_OWNER_INFO_COLOR, 0xFFFFFFFF);
        int alarmColor = Settings.System.getInt(resolver,
                Settings.System.LOCKSCREEN_ALARM_COLOR, 0xFFFFFFFF);
        int lockClockFont = Settings.System.getIntForUser(resolver,
                Settings.System.LOCK_CLOCK_FONTS, 4, UserHandle.USER_CURRENT);
        int iconNameValue = Settings.System.getInt(resolver,
                Settings.System.LOCK_SCREEN_WEATHER_CONDITION_ICON, 0);
        int secondaryTextColor = (179 << 24) | (mPrimaryTextColor & 0x00ffffff); // mPrimaryTextColor with a transparency of 70%
        if (forceHide) {
            mWeatherView.setVisibility(View.GONE);
        } else {
            if (mWeatherView != null) {
                mWeatherView.setVisibility(mShowWeather ? View.VISIBLE : View.GONE);
            }
        }
        if (mWeatherCity != null) {
            mWeatherCity.setVisibility(showLocation ? View.VISIBLE : View.INVISIBLE);
        }
        if (mWeatherCity != null) {
            mWeatherCity.setTextColor(mPrimaryTextColor);
        }
        if (mWeatherConditionText != null) {
            mWeatherConditionText.setTextColor(mPrimaryTextColor);
        }
        if (mWeatherCurrentTemp != null) {
            mWeatherCurrentTemp.setTextColor(secondaryTextColor);
        }

        mClockView = (TextClock) findViewById(R.id.clock_view);
        mClockView.setVisibility(showClock ? View.VISIBLE : View.INVISIBLE);

        mDateView = (TextClock) findViewById(R.id.date_view);
        mDateView.setVisibility(showDate ? View.VISIBLE : View.INVISIBLE);

        mAlarmStatusView = (TextView) findViewById(R.id.alarm_status);
        mAlarmStatusView.setVisibility(showAlarm && nextAlarm != null ? View.VISIBLE : View.GONE);

        switch (lockClockFont) {
            case 0:
                mClockView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                break;
            case 1:
                mClockView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                break;
            case 2:
                mClockView.setTypeface(Typeface.create("sans-serif", Typeface.ITALIC));
                break;
            case 3:
                mClockView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD_ITALIC));
                break;
            case 4:
                mClockView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                break;
            case 5:
                mClockView.setTypeface(Typeface.create("sans-serif-light", Typeface.ITALIC));
                break;
            case 6:
                mClockView.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
                break;
            case 7:
                mClockView.setTypeface(Typeface.create("sans-serif-thin", Typeface.ITALIC));
                break;
            case 8:
                mClockView.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
                break;
            case 9:
                mClockView.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
                break;
            case 10:
                mClockView.setTypeface(Typeface.create("sans-serif-condensed", Typeface.ITALIC));
                break;
            case 11:
                mClockView.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
                break;
            case 12:
                mClockView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                break;
            case 13:
                mClockView.setTypeface(Typeface.create("sans-serif-medium", Typeface.ITALIC));
                break;
            case 14:
                mClockView.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
                break;
            case 15:
                mClockView.setTypeface(Typeface.create("sans-serif-black", Typeface.ITALIC));
                break;
            case 16:
                mClockView.setTypeface(Typeface.create("sans-serif-condensed-light", Typeface.NORMAL));
                break;
            case 17:
                mClockView.setTypeface(Typeface.create("sans-serif-condensed-light", Typeface.ITALIC));
                break;
        }

        if (mClockView != null) {
            mClockView.setTextColor(clockColor);
        }

        if (mDateView != null) {
            mDateView.setTextColor(clockDateColor);
        }

        if (mOwnerInfo != null) {
            mOwnerInfo.setTextColor(ownerInfoColor);
        }

        if (mAlarmStatusView != null) {
            mAlarmStatusView.setTextColor(alarmColor);
        }

        if (mIconNameValue != iconNameValue) {
            mIconNameValue = iconNameValue;
            mWeatherController.updateWeather();
        }

        if (mWeatherConditionImage != null) {
            mWeatherConditionImage.setImageDrawable(null);
        }

        Drawable weatherIcon = mWeatherConditionDrawable;
        if (mIconColor == -2) {
            if (mWeatherConditionImage != null) {
                mWeatherConditionImage.setImageDrawable(weatherIcon);
            }
        } else {
            Bitmap coloredWeatherIcon =
                    ImageHelper.getColoredBitmap(weatherIcon, mIconColor);
            if (mWeatherConditionImage != null) {
                mWeatherConditionImage.setImageBitmap(coloredWeatherIcon);
            }
        }
    }

    // DateFormat.getBestDateTimePattern is extremely expensive, and refresh is called often.
    // This is an optimization to ensure we only recompute the patterns when the inputs change.
    private static final class Patterns {
        static String dateView;
        static String clockView12;
        static String clockView24;
        static String cacheKey;

        static void update(Context context, boolean hasAlarm) {
            final Locale locale = Locale.getDefault();
            final Resources res = context.getResources();
            final ContentResolver resolver = context.getContentResolver();
            final boolean showAlarm = Settings.System.getIntForUser(resolver,
                    Settings.System.HIDE_LOCKSCREEN_ALARM, 1, UserHandle.USER_CURRENT) == 1;
            final String dateViewSkel = res.getString(hasAlarm && showAlarm
                    ? R.string.abbrev_wday_month_day_no_year_alarm
                    : R.string.abbrev_wday_month_day_no_year);
            final String clockView12Skel = res.getString(R.string.clock_12hr_format);
            final String clockView24Skel = res.getString(R.string.clock_24hr_format);
            final String key = locale.toString() + dateViewSkel + clockView12Skel + clockView24Skel;
            if (key.equals(cacheKey)) return;

            dateView = DateFormat.getBestDateTimePattern(locale, dateViewSkel);

            clockView12 = DateFormat.getBestDateTimePattern(locale, clockView12Skel);
            // CLDR insists on adding an AM/PM indicator even though it wasn't in the skeleton
            // format.  The following code removes the AM/PM indicator if we didn't want it.
            if (!clockView12Skel.contains("a")) {
                clockView12 = clockView12.replaceAll("a", "").trim();
            }

            clockView24 = DateFormat.getBestDateTimePattern(locale, clockView24Skel);

            // Use fancy colon.
            clockView24 = clockView24.replace(':', '\uee01');
            clockView12 = clockView12.replace(':', '\uee01');

            cacheKey = key;
        }
    }
}
