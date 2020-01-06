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

package com.android.car.companiondevicesupport.api.internal.association;

import com.android.car.companiondevicesupport.api.internal.association.AssociatedDevice;
import com.android.car.companiondevicesupport.api.internal.association.IAssociationCallback;

/** Manager of devices associated with the car. */
interface IAssociatedDeviceManager {

    /** Starts the association with a new device. */
    void startAssociation(in IAssociationCallback callback);

    /** Stops the association with current device. */
    void stopAssociation(in IAssociationCallback callback);

    /** Returns {@link List<AssociatedDevice>} of devices associated with the given user. */
    List<AssociatedDevice> getActiveUserAssociatedDevices();

    /** Confirms the paring code. */
    void acceptVerification();
}