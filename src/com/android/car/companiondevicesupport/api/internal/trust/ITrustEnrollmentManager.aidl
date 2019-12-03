/**
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

package com.android.car.companiondevicesupport.api.internal.trust;

import com.android.car.companiondevicesupport.api.internal.trust.ITrustEnrollmentCallback;

/** Manager of trusted devices with the car. */
interface ITrustEnrollmentManager {

    /** Register for enrollment triggered events. */
    void registerCallback(in ITrustEnrollmentCallback callback);

    /** Remove a previously registered callback. */
    void unregisterCallback(in ITrustEnrollmentCallback callback);
}