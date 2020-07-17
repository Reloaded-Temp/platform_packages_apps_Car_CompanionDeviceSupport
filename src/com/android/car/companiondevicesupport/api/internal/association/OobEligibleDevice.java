/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.car.connecteddevice.model.OobEligibleDevice.OobType;

import java.util.Objects;

/** Device that may be used for an out-of-band channel. */
public final class OobEligibleDevice implements Parcelable {

    private final String mDeviceAddress;

    @OobType
    private final int mOobType;

    public OobEligibleDevice(@NonNull String deviceAddress, @OobType int oobType) {
        mDeviceAddress = deviceAddress;
        mOobType = oobType;
    }

    public OobEligibleDevice(
            @NonNull com.android.car.connecteddevice.model.OobEligibleDevice device) {
        this(device.getDeviceAddress(), device.getOobType());
    }

    private OobEligibleDevice(Parcel in) {
        this(in.readString(), in.readInt());
    }

    @NonNull
    public String getDeviceAddress() {
        return mDeviceAddress;
    }

    @OobType
    public int getOobType() {
        return mOobType;
    }

    public com.android.car.connecteddevice.model.OobEligibleDevice toModel() {
        return new com.android.car.connecteddevice.model.OobEligibleDevice(mDeviceAddress,
                mOobType);
    }


    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mDeviceAddress);
        dest.writeInt(mOobType);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof OobEligibleDevice)) {
            return false;
        }
        OobEligibleDevice device = (OobEligibleDevice) obj;
        return Objects.equals(device.mDeviceAddress, mDeviceAddress)
                && device.mOobType == mOobType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDeviceAddress, mOobType);
    }

    public static final Parcelable.Creator<OobEligibleDevice> CREATOR =
            new Parcelable.Creator<OobEligibleDevice>() {
        @Override
        public OobEligibleDevice createFromParcel(Parcel source) {
            return new OobEligibleDevice(source);
        }

        @Override
        public OobEligibleDevice[] newArray(int size) {
            return new OobEligibleDevice[size];
        }
    };
}
