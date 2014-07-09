/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.telecomm;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.DisplayMetrics;

import java.util.MissingResourceException;

/**
 * Contains status label and icon displayed in the in-call UI.
 */
public final class StatusHints implements Parcelable {

    private final ComponentName mComponentName;
    private final String mLabel;
    private final int mIconId;

    public StatusHints(ComponentName componentName, String label, int iconId) {
        mComponentName = componentName;
        mLabel = label;
        mIconId = iconId;
    }

    /**
     * @return A component used to load the icon.
     */
    public ComponentName getComponentName() {
        return mComponentName;
    }

    /**
     * @return The label displayed in the in-call UI.
     */
    public String getLabel() {
        return mLabel;
    }

    /**
     * @return The icon resource identifier.
     */
    public int getIconId() {
        return mIconId;
    }

    /**
     * @return An icon displayed in the in-call UI.
     */
    public Drawable getIcon(Context context) {
        return getIcon(context, mIconId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeParcelable(mComponentName, flags);
        out.writeString(mLabel);
        out.writeInt(mIconId);
    }

    public static final Creator<StatusHints> CREATOR
            = new Creator<StatusHints>() {
        public StatusHints createFromParcel(Parcel in) {
            return new StatusHints(in);
        }

        public StatusHints[] newArray(int size) {
            return new StatusHints[size];
        }
    };

    private StatusHints(Parcel in) {
        mComponentName = in.readParcelable(getClass().getClassLoader());
        mLabel = in.readString();
        mIconId = in.readInt();
    }

    private Drawable getIcon(Context context, int resId) {
        Context packageContext;
        try {
            packageContext = context.createPackageContext(mComponentName.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(this, e, "Cannot find package %s", mComponentName.getPackageName());
            return null;
        }
        try {
            return packageContext.getResources().getDrawable(resId);
        } catch (MissingResourceException e) {
            Log.e(this, e, "Cannot find icon %d in package %s",
                    resId, mComponentName.getPackageName());
            return null;
        }
    }
}
