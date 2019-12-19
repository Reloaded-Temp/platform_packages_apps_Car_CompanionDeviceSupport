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

package com.android.car.companiondevicesupport.feature.trust;

/** Constants for trusted device feature. */
public class TrustedDeviceConstants {

    private TrustedDeviceConstants() { }

    /**
     * Intent extra key for a boolean signalling a new escrow token is being enrolled.
     */
    public static final String INTENT_EXTRA_ENROLL_NEW_TOKEN = "trusted.device.enrolling.token";
}
