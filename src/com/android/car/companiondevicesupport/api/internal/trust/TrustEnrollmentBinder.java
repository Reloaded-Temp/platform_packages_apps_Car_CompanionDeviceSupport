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

package com.android.car.companiondevicesupport.api.internal.trust;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/** Binder for exposing trusted device enrollment actions. */
public class TrustEnrollmentBinder extends ITrustEnrollmentManager.Stub {

    private static final String TAG = "TrustEnrollmentBinder";

    private final Set<ITrustEnrollmentCallback> mCallbacks = new CopyOnWriteArraySet<>();

    // TODO (b/144163031) Finish implementation with TrustedDeviceManager.

    @Override
    public void registerCallback(ITrustEnrollmentCallback callback) {
        mCallbacks.add(callback);
    }

    @Override
    public void unregisterCallback(ITrustEnrollmentCallback callback) {
        mCallbacks.remove(callback);
    }

}
