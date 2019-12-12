/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.car.companiondevicesupport.feature.calendarsync;

import static com.android.car.connecteddevice.util.SafeLog.loge;

import static java.util.concurrent.TimeUnit.SECONDS;

import android.annotation.NonNull;

import com.android.car.companiondevicesupport.feature.calendarsync.proto.TimeZone;
import com.android.car.companiondevicesupport.feature.calendarsync.proto.Timestamp;

import java.util.Calendar;
import java.util.Date;

/**
 * A utility class that deals with the conversion of timestamps.
 */
final class TimestampConverter {
    private static final String TAG = "TimestampConverter";

    private static final java.util.TimeZone UTC = java.util.TimeZone.getTimeZone("UTC");

    private TimestampConverter() {
    }

    /**
     * Converts a timestamp into a EventDateTime. If isAllDay is true, round down (if isStart is
     * true) or round up (if isStart is false) to the day boundary.
     */
    static long convertTimestamp(@NonNull Timestamp protoTimestamp, TimeZone protoTimeZone,
            boolean isAllDay, boolean isStart) {
        return convertTimestamp(protoTimestamp.getSeconds(), protoTimeZone, isAllDay, isStart);
    }

    private static long convertTimestamp(long timestampInSeconds, TimeZone protoTimeZone,
            boolean isAllDay, boolean isStart) {
        java.util.TimeZone timeZone = convertTimeZone(protoTimeZone);
        Calendar calendar = Calendar.getInstance(timeZone);
        calendar.setTime(new Date(SECONDS.toMillis(timestampInSeconds)));
        if (isAllDay && !isStart) {
            calendar.add(java.util.Calendar.DAY_OF_MONTH, 1);
        }
        return calendar.getTime().getTime();
    }

    private static java.util.TimeZone convertTimeZone(TimeZone protoTimeZone) {
        if (protoTimeZone == null) {
            loge(TAG, "Missing timezone", null);
            return UTC;
        }
        return convertTimeZone(protoTimeZone.getName(), protoTimeZone.getSecondsFromGmt());
    }

    private static java.util.TimeZone convertTimeZone(String timeZoneName, long secondsFromGmt) {
        // iOS' timezone id may not be the same as Android's or Google Calendar API's.
        // Assume we can use the timezone string as in iOS's db for now.
        java.util.TimeZone timeZone = java.util.TimeZone.getTimeZone(timeZoneName);
        if (timeZone != null) {
            return timeZone;
        }
        // Retrieve a timezone from the provided secondsFromGmt value. This could potentially
        // result into multiple multiple timezones. As the timezone is never exposed externally the
        // first available timezone is picked.
        try {
            return java.util.TimeZone.getTimeZone(java.util.TimeZone.getAvailableIDs(
                    Math.toIntExact(SECONDS.toMillis(secondsFromGmt)))[0]);
        } catch (ArrayIndexOutOfBoundsException | ArithmeticException e) {
            loge(TAG, "Failed to convert '" + timeZoneName + "' with offset "
                    + secondsFromGmt + "secs into TimeZone", e);
        }
        return UTC;
    }
}
