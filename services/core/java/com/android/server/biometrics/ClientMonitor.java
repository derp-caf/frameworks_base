/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.biometrics;

import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.media.AudioAttributes;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Slog;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.internal.logging.MetricsLogger;

import java.util.ArrayList;
import java.util.NoSuchElementException;

/**
 * Abstract base class for keeping track and dispatching events from the biometric's HAL to the
 * the current client.  Subclasses are responsible for coordinating the interaction with
 * the biometric's HAL for the specific action (e.g. authenticate, enroll, enumerate, etc.).
 */
public abstract class ClientMonitor extends LoggableMonitor implements IBinder.DeathRecipient {
    protected static final int ERROR_ESRCH = 3; // Likely HAL is dead. See errno.h.
    protected static final boolean DEBUG = BiometricServiceBase.DEBUG;
    private static final AudioAttributes FINGERPRINT_SONFICATION_ATTRIBUTES =
            new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .build();

    private final Context mContext;
    private final long mHalDeviceId;
    private final int mTargetUserId;
    private final int mGroupId;
    // True if client does not have MANAGE_FINGERPRINT permission
    private final boolean mIsRestricted;
    private final String mOwner;
    private final VibrationEffect mSuccessVibrationEffect;
    private final VibrationEffect mErrorVibrationEffect;
    private final BiometricServiceBase.DaemonWrapper mDaemon;

    private IBinder mToken;
    private BiometricServiceBase.ServiceListener mListener;
    // Currently only used for authentication client. The cookie generated by BiometricService
    // is never 0.
    private final int mCookie;

    protected final MetricsLogger mMetricsLogger;
    protected final Constants mConstants;

    protected boolean mAlreadyCancelled;
    protected boolean mAlreadyDone;

    /**
     * @param context context of BiometricService
     * @param daemon interface to call back to a specific biometric's daemon
     * @param halDeviceId the HAL device ID of the associated biometric hardware
     * @param token a unique token for the client
     * @param listener recipient of related events (e.g. authentication)
     * @param userId target user id for operation
     * @param groupId groupId for the fingerprint set
     * @param restricted whether or not client has the MANAGE_* permission
     * permission
     * @param owner name of the client that owns this
     */
    public ClientMonitor(Context context, Constants constants,
            BiometricServiceBase.DaemonWrapper daemon, long halDeviceId, IBinder token,
            BiometricServiceBase.ServiceListener listener, int userId, int groupId,
            boolean restricted, String owner, int cookie) {
        mContext = context;
        mConstants = constants;
        mDaemon = daemon;
        mHalDeviceId = halDeviceId;
        mToken = token;
        mListener = listener;
        mTargetUserId = userId;
        mGroupId = groupId;
        mIsRestricted = restricted;
        mOwner = owner;
        mCookie = cookie;
        mSuccessVibrationEffect = VibrationEffect.get(VibrationEffect.EFFECT_CLICK);
        mErrorVibrationEffect = VibrationEffect.get(VibrationEffect.EFFECT_DOUBLE_CLICK);
        mMetricsLogger = new MetricsLogger();
        try {
            if (token != null) {
                token.linkToDeath(this, 0);
            }
        } catch (RemoteException e) {
            Slog.w(getLogTag(), "caught remote exception in linkToDeath: ", e);
        }
    }

    protected String getLogTag() {
        return mConstants.logTag();
    }

    public int getCookie() {
        return mCookie;
    }

    /**
     * Contacts the biometric's HAL to start the client.
     * @return 0 on success, errno from driver on failure
     */
    public abstract int start();

    /**
     * Contacts the biometric's HAL to stop the client.
     * @param initiatedByClient whether the operation is at the request of a client
     */
    public abstract int stop(boolean initiatedByClient);

    /**
     * Method to explicitly poke powermanager on events
     */
    public abstract void notifyUserActivity();

    // Event callbacks from driver. Inappropriate calls is flagged/logged by the
    // respective client (e.g. enrolling shouldn't get authenticate events).
    // All of these return 'true' if the operation is completed and it's ok to move
    // to the next client (e.g. authentication accepts or rejects a biometric).
    public abstract boolean onEnrollResult(BiometricAuthenticator.Identifier identifier,
            int remaining);
    public abstract boolean onAuthenticated(BiometricAuthenticator.Identifier identifier,
            boolean authenticated, ArrayList<Byte> token);
    public abstract boolean onRemoved(BiometricAuthenticator.Identifier identifier,
            int remaining);
    public abstract boolean onEnumerationResult(
            BiometricAuthenticator.Identifier identifier, int remaining);

    public int[] getAcquireIgnorelist() {
        return new int[0];
    }
    public int[] getAcquireVendorIgnorelist() {
        return new int[0];
    }

    private boolean blacklistContains(int acquiredInfo, int vendorCode) {
        if (acquiredInfo == mConstants.acquireVendorCode()) {
            for (int i = 0; i < getAcquireVendorIgnorelist().length; i++) {
                if (getAcquireVendorIgnorelist()[i] == vendorCode) {
                    if (DEBUG) Slog.v(getLogTag(), "Ignoring vendor message: " + vendorCode);
                    return true;
                }
            }
        } else {
            for (int i = 0; i < getAcquireIgnorelist().length; i++) {
                if (getAcquireIgnorelist()[i] == acquiredInfo) {
                    if (DEBUG) Slog.v(getLogTag(), "Ignoring message: " + acquiredInfo);
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isAlreadyDone() {
        return mAlreadyDone;
    }

    /**
     * Called when we get notification from the biometric's HAL that an image has been acquired.
     * Common to authenticate and enroll.
     * @param acquiredInfo info about the current image acquisition
     * @return true if client should be removed
     */
    public boolean onAcquired(int acquiredInfo, int vendorCode) {
        super.logOnAcquired(mContext, acquiredInfo, vendorCode, getTargetUserId());
        if (DEBUG) Slog.v(getLogTag(), "Acquired: " + acquiredInfo + " " + vendorCode);
        try {
            if (mListener != null && !blacklistContains(acquiredInfo, vendorCode)) {
                mListener.onAcquired(getHalDeviceId(), acquiredInfo, vendorCode);
            }
            return false; // acquisition continues...
        } catch (RemoteException e) {
            Slog.w(getLogTag(), "Failed to invoke sendAcquired", e);
            return true;
        } finally {
            // Good scans will keep the device awake
            if (acquiredInfo == BiometricConstants.BIOMETRIC_ACQUIRED_GOOD) {
                notifyUserActivity();
            }
        }
    }

    /**
     * Called when we get notification from the biometric's HAL that an error has occurred with the
     * current operation. Common to authenticate, enroll, enumerate and remove.
     * @param error
     * @return true if client should be removed
     */
    public boolean onError(long deviceId, int error, int vendorCode) {
        super.logOnError(mContext, error, vendorCode, getTargetUserId());
        try {
            if (mListener != null) {
                mListener.onError(deviceId, error, vendorCode, getCookie());
            }
        } catch (RemoteException e) {
            Slog.w(getLogTag(), "Failed to invoke sendError", e);
        }
        return true; // errors always remove current client
    }

    public void destroy() {
        if (mToken != null) {
            try {
                mToken.unlinkToDeath(this, 0);
            } catch (NoSuchElementException e) {
                // TODO: remove when duplicate call bug is found
                Slog.e(getLogTag(), "destroy(): " + this + ":", new Exception("here"));
            }
            mToken = null;
        }
        mListener = null;
    }

    @Override
    public void binderDied() {
        binderDiedInternal(true /* clearListener */);
    }

    void binderDiedInternal(boolean clearListener) {
        // If the current client dies we should cancel the current operation.
        Slog.e(getLogTag(), "Binder died, cancelling client");
        stop(false /* initiatedByClient */);
        mToken = null;
        if (clearListener) {
            mListener = null;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mToken != null) {
                if (DEBUG) Slog.w(getLogTag(), "removing leaked reference: " + mToken);
                onError(getHalDeviceId(), BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                        0 /* vendorCode */);
            }
        } finally {
            super.finalize();
        }
    }

    public final Context getContext() {
        return mContext;
    }

    public final long getHalDeviceId() {
        return mHalDeviceId;
    }

    public final String getOwnerString() {
        return mOwner;
    }

    public final BiometricServiceBase.ServiceListener getListener() {
        return mListener;
    }

    public final BiometricServiceBase.DaemonWrapper getDaemonWrapper() {
        return mDaemon;
    }

    public final boolean getIsRestricted() {
        return mIsRestricted;
    }

    public final int getTargetUserId() {
        return mTargetUserId;
    }

    public final int getGroupId() {
        return mGroupId;
    }

    public final IBinder getToken() {
        return mToken;
    }

    public final void vibrateSuccess() {
        Vibrator vibrator = mContext.getSystemService(Vibrator.class);
        boolean FingerprintVibSuccess = Settings.System.getIntForUser(mContext.getContentResolver(),
            Settings.System.FINGERPRINT_SUCCESS_VIB, 1, UserHandle.USER_CURRENT) == 1;
        if (vibrator != null && FingerprintVibSuccess) {
            vibrator.vibrate(mSuccessVibrationEffect, FINGERPRINT_SONFICATION_ATTRIBUTES);
        }
    }

    public final void vibrateError() {
        Vibrator vibrator = mContext.getSystemService(Vibrator.class);
        boolean FingerprintVibError = Settings.System.getIntForUser(mContext.getContentResolver(),
            Settings.System.FINGERPRINT_ERROR_VIB, 1, UserHandle.USER_CURRENT) == 1;
        if (vibrator != null && FingerprintVibError) {
            vibrator.vibrate(mErrorVibrationEffect, FINGERPRINT_SONFICATION_ATTRIBUTES);
        }
    }
}
