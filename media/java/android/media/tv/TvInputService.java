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

package android.media.tv;

import android.annotation.FloatRange;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.app.ActivityManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.CaptioningManager;
import android.widget.FrameLayout;

import com.android.internal.os.SomeArgs;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The TvInputService class represents a TV input or source such as HDMI or built-in tuner which
 * provides pass-through video or broadcast TV programs.
 *
 * <p>Applications will not normally use this service themselves, instead relying on the standard
 * interaction provided by {@link TvView}. Those implementing TV input services should normally do
 * so by deriving from this class and providing their own session implementation based on
 * {@link TvInputService.Session}. All TV input services must require that clients hold the
 * {@link android.Manifest.permission#BIND_TV_INPUT} in order to interact with the service; if this
 * permission is not specified in the manifest, the system will refuse to bind to that TV input
 * service.
 */
public abstract class TvInputService extends Service {
    private static final boolean DEBUG = false;
    private static final String TAG = "TvInputService";

    private static final int DETACH_OVERLAY_VIEW_TIMEOUT_MS = 5000;

    /**
     * This is the interface name that a service implementing a TV input should say that it support
     * -- that is, this is the action it uses for its intent filter. To be supported, the service
     * must also require the {@link android.Manifest.permission#BIND_TV_INPUT} permission so that
     * other applications cannot abuse it.
     */
    public static final String SERVICE_INTERFACE = "android.media.tv.TvInputService";

    /**
     * Name under which a TvInputService component publishes information about itself.
     * This meta-data must reference an XML resource containing an
     * <code>&lt;{@link android.R.styleable#TvInputService tv-input}&gt;</code>
     * tag.
     */
    public static final String SERVICE_META_DATA = "android.media.tv.input";

    /**
     * Handler instance to handle request from TV Input Manager Service. Should be run in the main
     * looper to be synchronously run with {@code Session.mHandler}.
     */
    private final Handler mServiceHandler = new ServiceHandler();
    private final RemoteCallbackList<ITvInputServiceCallback> mCallbacks =
            new RemoteCallbackList<>();

    private TvInputManager mTvInputManager;

    @Override
    public final IBinder onBind(Intent intent) {
        return new ITvInputService.Stub() {
            @Override
            public void registerCallback(ITvInputServiceCallback cb) {
                if (cb != null) {
                    mCallbacks.register(cb);
                }
            }

            @Override
            public void unregisterCallback(ITvInputServiceCallback cb) {
                if (cb != null) {
                    mCallbacks.unregister(cb);
                }
            }

            @Override
            public void createSession(InputChannel channel, ITvInputSessionCallback cb,
                    String inputId) {
                if (channel == null) {
                    Log.w(TAG, "Creating session without input channel");
                }
                if (cb == null) {
                    return;
                }
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = channel;
                args.arg2 = cb;
                args.arg3 = inputId;
                mServiceHandler.obtainMessage(ServiceHandler.DO_CREATE_SESSION, args).sendToTarget();
            }

            @Override
            public void createRecordingSession(ITvInputSessionCallback cb, String inputId) {
                if (cb == null) {
                    return;
                }
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = cb;
                args.arg2 = inputId;
                mServiceHandler.obtainMessage(ServiceHandler.DO_CREATE_RECORDING_SESSION, args)
                        .sendToTarget();
            }

            @Override
            public void notifyHardwareAdded(TvInputHardwareInfo hardwareInfo) {
                mServiceHandler.obtainMessage(ServiceHandler.DO_ADD_HARDWARE_INPUT,
                        hardwareInfo).sendToTarget();
            }

            @Override
            public void notifyHardwareRemoved(TvInputHardwareInfo hardwareInfo) {
                mServiceHandler.obtainMessage(ServiceHandler.DO_REMOVE_HARDWARE_INPUT,
                        hardwareInfo).sendToTarget();
            }

            @Override
            public void notifyHdmiDeviceAdded(HdmiDeviceInfo deviceInfo) {
                mServiceHandler.obtainMessage(ServiceHandler.DO_ADD_HDMI_INPUT,
                        deviceInfo).sendToTarget();
            }

            @Override
            public void notifyHdmiDeviceRemoved(HdmiDeviceInfo deviceInfo) {
                mServiceHandler.obtainMessage(ServiceHandler.DO_REMOVE_HDMI_INPUT,
                        deviceInfo).sendToTarget();
            }
        };
    }

    /**
     * Returns a concrete implementation of {@link Session}.
     *
     * <p>May return {@code null} if this TV input service fails to create a session for some
     * reason. If TV input represents an external device connected to a hardware TV input,
     * {@link HardwareSession} should be returned.
     *
     * @param inputId The ID of the TV input associated with the session.
     */
    @Nullable
    public abstract Session onCreateSession(String inputId);

    /**
     * Returns a concrete implementation of {@link RecordingSession}.
     *
     * <p>May return {@code null} if this TV input service fails to create a recording session for
     * some reason.
     *
     * @param inputId The ID of the TV input associated with the recording session.
     */
    @Nullable
    public RecordingSession onCreateRecordingSession(String inputId) {
        return null;
    }

    /**
     * Returns a new {@link TvInputInfo} object if this service is responsible for
     * {@code hardwareInfo}; otherwise, return {@code null}. Override to modify default behavior of
     * ignoring all hardware input.
     *
     * @param hardwareInfo {@link TvInputHardwareInfo} object just added.
     * @hide
     */
    @Nullable
    @SystemApi
    public TvInputInfo onHardwareAdded(TvInputHardwareInfo hardwareInfo) {
        return null;
    }

    /**
     * Returns the input ID for {@code deviceId} if it is handled by this service;
     * otherwise, return {@code null}. Override to modify default behavior of ignoring all hardware
     * input.
     *
     * @param hardwareInfo {@link TvInputHardwareInfo} object just removed.
     * @hide
     */
    @Nullable
    @SystemApi
    public String onHardwareRemoved(TvInputHardwareInfo hardwareInfo) {
        return null;
    }

    /**
     * Returns a new {@link TvInputInfo} object if this service is responsible for
     * {@code deviceInfo}; otherwise, return {@code null}. Override to modify default behavior of
     * ignoring all HDMI logical input device.
     *
     * @param deviceInfo {@link HdmiDeviceInfo} object just added.
     * @hide
     */
    @Nullable
    @SystemApi
    public TvInputInfo onHdmiDeviceAdded(HdmiDeviceInfo deviceInfo) {
        return null;
    }

    /**
     * Returns the input ID for {@code deviceInfo} if it is handled by this service; otherwise,
     * return {@code null}. Override to modify default behavior of ignoring all HDMI logical input
     * device.
     *
     * @param deviceInfo {@link HdmiDeviceInfo} object just removed.
     * @hide
     */
    @Nullable
    @SystemApi
    public String onHdmiDeviceRemoved(HdmiDeviceInfo deviceInfo) {
        return null;
    }

    private boolean isPassthroughInput(String inputId) {
        if (mTvInputManager == null) {
            mTvInputManager = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        }
        TvInputInfo info = mTvInputManager.getTvInputInfo(inputId);
        return info != null && info.isPassthroughInput();
    }

    /**
     * Base class for derived classes to implement to provide a TV input session.
     */
    public abstract static class Session implements KeyEvent.Callback {
        private static final int POSITION_UPDATE_INTERVAL_MS = 1000;

        private final KeyEvent.DispatcherState mDispatcherState = new KeyEvent.DispatcherState();
        private final WindowManager mWindowManager;
        final Handler mHandler;
        private WindowManager.LayoutParams mWindowParams;
        private Surface mSurface;
        private final Context mContext;
        private FrameLayout mOverlayViewContainer;
        private View mOverlayView;
        private OverlayViewCleanUpTask mOverlayViewCleanUpTask;
        private boolean mOverlayViewEnabled;
        private IBinder mWindowToken;
        private Rect mOverlayFrame;
        private long mStartPositionMs = TvInputManager.TIME_SHIFT_INVALID_TIME;
        private long mCurrentPositionMs = TvInputManager.TIME_SHIFT_INVALID_TIME;
        private final TimeShiftPositionTrackingRunnable
                mTimeShiftPositionTrackingRunnable = new TimeShiftPositionTrackingRunnable();

        private final Object mLock = new Object();
        // @GuardedBy("mLock")
        private ITvInputSessionCallback mSessionCallback;
        // @GuardedBy("mLock")
        private final List<Runnable> mPendingActions = new ArrayList<>();

        /**
         * Creates a new Session.
         *
         * @param context The context of the application
         */
        public Session(Context context) {
            mContext = context;
            mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            mHandler = new Handler(context.getMainLooper());
        }

        /**
         * Enables or disables the overlay view.
         *
         * <p>By default, the overlay view is disabled. Must be called explicitly after the
         * session is created to enable the overlay view.
         *
         * <p>The TV input service can disable its overlay view when the size of the overlay view is
         * insufficient to display the whole information, such as when used in Picture-in-picture.
         * Override {@link #onOverlayViewSizeChanged} to get the size of the overlay view, which
         * then can be used to determine whether to enable/disable the overlay view.
         *
         * @param enable {@code true} if you want to enable the overlay view. {@code false}
         *            otherwise.
         */
        public void setOverlayViewEnabled(final boolean enable) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (enable == mOverlayViewEnabled) {
                        return;
                    }
                    mOverlayViewEnabled = enable;
                    if (enable) {
                        if (mWindowToken != null) {
                            createOverlayView(mWindowToken, mOverlayFrame);
                        }
                    } else {
                        removeOverlayView(false);
                    }
                }
            });
        }

        /**
         * Dispatches an event to the application using this session.
         *
         * @param eventType The type of the event.
         * @param eventArgs Optional arguments of the event.
         * @hide
         */
        @SystemApi
        public void notifySessionEvent(@NonNull final String eventType, final Bundle eventArgs) {
            Preconditions.checkNotNull(eventType);
            executeOrPostRunnableOnMainThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "notifySessionEvent(" + eventType + ")");
                        if (mSessionCallback != null) {
                            mSessionCallback.onSessionEvent(eventType, eventArgs);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in sending event (event=" + eventType + ")", e);
                    }
                }
            });
        }

        /**
         * Informs the application that the current channel is re-tuned for some reason and the
         * session now displays the content from a new channel. This is used to handle special cases
         * such as when the current channel becomes unavailable, it is necessary to send the user to
         * a certain channel or the user changes channel in some other way (e.g. by using a
         * dedicated remote).
         *
         * @param channelUri The URI of the new channel.
         */
        public void notifyChannelRetuned(final Uri channelUri) {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "notifyChannelRetuned");
                        if (mSessionCallback != null) {
                            mSessionCallback.onChannelRetuned(channelUri);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifyChannelRetuned", e);
                    }
                }
            });
        }

        /**
         * Sends the list of all audio/video/subtitle tracks. The is used by the framework to
         * maintain the track information for a given session, which in turn is used by
         * {@link TvView#getTracks} for the application to retrieve metadata for a given track type.
         * The TV input service must call this method as soon as the track information becomes
         * available or is updated. Note that in a case where a part of the information for a
         * certain track is updated, it is not necessary to create a new {@link TvTrackInfo} object
         * with a different track ID.
         *
         * @param tracks A list which includes track information.
         */
        public void notifyTracksChanged(final List<TvTrackInfo> tracks) {
            final List<TvTrackInfo> tracksCopy = new ArrayList<>(tracks);
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "notifyTracksChanged");
                        if (mSessionCallback != null) {
                            mSessionCallback.onTracksChanged(tracksCopy);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifyTracksChanged", e);
                    }
                }
            });
        }

        /**
         * Sends the type and ID of a selected track. This is used to inform the application that a
         * specific track is selected. The TV input service must call this method as soon as a track
         * is selected either by default or in response to a call to {@link #onSelectTrack}. The
         * selected track ID for a given type is maintained in the framework until the next call to
         * this method even after the entire track list is updated (but is reset when the session is
         * tuned to a new channel), so care must be taken not to result in an obsolete track ID.
         *
         * @param type The type of the selected track. The type can be
         *            {@link TvTrackInfo#TYPE_AUDIO}, {@link TvTrackInfo#TYPE_VIDEO} or
         *            {@link TvTrackInfo#TYPE_SUBTITLE}.
         * @param trackId The ID of the selected track.
         * @see #onSelectTrack
         */
        public void notifyTrackSelected(final int type, final String trackId) {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "notifyTrackSelected");
                        if (mSessionCallback != null) {
                            mSessionCallback.onTrackSelected(type, trackId);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifyTrackSelected", e);
                    }
                }
            });
        }

        /**
         * Informs the application that the video is now available for watching. Video is blocked
         * until this method is called.
         *
         * <p>The TV input service must call this method as soon as the content rendered onto its
         * surface is ready for viewing. This method must be called each time {@link #onTune}
         * is called.
         *
         * @see #notifyVideoUnavailable
         */
        public void notifyVideoAvailable() {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "notifyVideoAvailable");
                        if (mSessionCallback != null) {
                            mSessionCallback.onVideoAvailable();
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifyVideoAvailable", e);
                    }
                }
            });
        }

        /**
         * Informs the application that the video became unavailable for some reason. This is
         * primarily used to signal the application to block the screen not to show any intermittent
         * video artifacts.
         *
         * @param reason The reason why the video became unavailable:
         *            <ul>
         *            <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_UNKNOWN}
         *            <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_TUNING}
         *            <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL}
         *            <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_BUFFERING}
         *            <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY}
         *            </ul>
         * @see #notifyVideoAvailable
         */
        public void notifyVideoUnavailable(
                @TvInputManager.VideoUnavailableReason final int reason) {
            if (reason < TvInputManager.VIDEO_UNAVAILABLE_REASON_START
                    || reason > TvInputManager.VIDEO_UNAVAILABLE_REASON_END) {
                Log.e(TAG, "notifyVideoUnavailable - unknown reason: " + reason);
            }
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "notifyVideoUnavailable");
                        if (mSessionCallback != null) {
                            mSessionCallback.onVideoUnavailable(reason);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifyVideoUnavailable", e);
                    }
                }
            });
        }

        /**
         * Informs the application that the user is allowed to watch the current program content.
         *
         * <p>Each TV input service is required to query the system whether the user is allowed to
         * watch the current program before showing it to the user if the parental controls is
         * enabled (i.e. {@link TvInputManager#isParentalControlsEnabled
         * TvInputManager.isParentalControlsEnabled()} returns {@code true}). Whether the TV input
         * service should block the content or not is determined by invoking
         * {@link TvInputManager#isRatingBlocked TvInputManager.isRatingBlocked(TvContentRating)}
         * with the content rating for the current program. Then the {@link TvInputManager} makes a
         * judgment based on the user blocked ratings stored in the secure settings and returns the
         * result. If the rating in question turns out to be allowed by the user, the TV input
         * service must call this method to notify the application that is permitted to show the
         * content.
         *
         * <p>Each TV input service also needs to continuously listen to any changes made to the
         * parental controls settings by registering a broadcast receiver to receive
         * {@link TvInputManager#ACTION_BLOCKED_RATINGS_CHANGED} and
         * {@link TvInputManager#ACTION_PARENTAL_CONTROLS_ENABLED_CHANGED} and immediately
         * reevaluate the current program with the new parental controls settings.
         *
         * @see #notifyContentBlocked
         * @see TvInputManager
         */
        public void notifyContentAllowed() {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "notifyContentAllowed");
                        if (mSessionCallback != null) {
                            mSessionCallback.onContentAllowed();
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifyContentAllowed", e);
                    }
                }
            });
        }

        /**
         * Informs the application that the current program content is blocked by parent controls.
         *
         * <p>Each TV input service is required to query the system whether the user is allowed to
         * watch the current program before showing it to the user if the parental controls is
         * enabled (i.e. {@link TvInputManager#isParentalControlsEnabled
         * TvInputManager.isParentalControlsEnabled()} returns {@code true}). Whether the TV input
         * service should block the content or not is determined by invoking
         * {@link TvInputManager#isRatingBlocked TvInputManager.isRatingBlocked(TvContentRating)}
         * with the content rating for the current program or {@link TvContentRating#UNRATED} in
         * case the rating information is missing. Then the {@link TvInputManager} makes a judgment
         * based on the user blocked ratings stored in the secure settings and returns the result.
         * If the rating in question turns out to be blocked, the TV input service must immediately
         * block the content and call this method with the content rating of the current program to
         * prompt the PIN verification screen.
         *
         * <p>Each TV input service also needs to continuously listen to any changes made to the
         * parental controls settings by registering a broadcast receiver to receive
         * {@link TvInputManager#ACTION_BLOCKED_RATINGS_CHANGED} and
         * {@link TvInputManager#ACTION_PARENTAL_CONTROLS_ENABLED_CHANGED} and immediately
         * reevaluate the current program with the new parental controls settings.
         *
         * @param rating The content rating for the current TV program. Can be
         *            {@link TvContentRating#UNRATED}.
         * @see #notifyContentAllowed
         * @see TvInputManager
         */
        public void notifyContentBlocked(@NonNull final TvContentRating rating) {
            Preconditions.checkNotNull(rating);
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "notifyContentBlocked");
                        if (mSessionCallback != null) {
                            mSessionCallback.onContentBlocked(rating.flattenToString());
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifyContentBlocked", e);
                    }
                }
            });
        }

        /**
         * Informs the application that the time shift status is changed.
         *
         * <p>Prior to calling this method, the application assumes the status
         * {@link TvInputManager#TIME_SHIFT_STATUS_UNKNOWN}. Right after the session is created, it
         * is important to invoke the method with the status
         * {@link TvInputManager#TIME_SHIFT_STATUS_AVAILABLE} if the implementation does support
         * time shifting, or {@link TvInputManager#TIME_SHIFT_STATUS_UNSUPPORTED} otherwise. Failure
         * to notifying the current status change immediately might result in an undesirable
         * behavior in the application such as hiding the play controls.
         *
         * <p>If the status {@link TvInputManager#TIME_SHIFT_STATUS_AVAILABLE} is reported, the
         * application assumes it can pause/resume playback, seek to a specified time position and
         * set playback rate and audio mode. The implementation should override
         * {@link #onTimeShiftPause}, {@link #onTimeShiftResume}, {@link #onTimeShiftSeekTo},
         * {@link #onTimeShiftGetStartPosition}, {@link #onTimeShiftGetCurrentPosition} and
         * {@link #onTimeShiftSetPlaybackParams}.
         *
         * @param status The current time shift status. Should be one of the followings.
         * <ul>
         * <li>{@link TvInputManager#TIME_SHIFT_STATUS_UNSUPPORTED}
         * <li>{@link TvInputManager#TIME_SHIFT_STATUS_UNAVAILABLE}
         * <li>{@link TvInputManager#TIME_SHIFT_STATUS_AVAILABLE}
         * </ul>
         */
        public void notifyTimeShiftStatusChanged(@TvInputManager.TimeShiftStatus final int status) {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    timeShiftEnablePositionTracking(
                            status == TvInputManager.TIME_SHIFT_STATUS_AVAILABLE);
                    try {
                        if (DEBUG) Log.d(TAG, "notifyTimeShiftStatusChanged");
                        if (mSessionCallback != null) {
                            mSessionCallback.onTimeShiftStatusChanged(status);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifyTimeShiftStatusChanged", e);
                    }
                }
            });
        }

        private void notifyTimeShiftStartPositionChanged(final long timeMs) {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "notifyTimeShiftStartPositionChanged");
                        if (mSessionCallback != null) {
                            mSessionCallback.onTimeShiftStartPositionChanged(timeMs);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifyTimeShiftStartPositionChanged", e);
                    }
                }
            });
        }

        private void notifyTimeShiftCurrentPositionChanged(final long timeMs) {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "notifyTimeShiftCurrentPositionChanged");
                        if (mSessionCallback != null) {
                            mSessionCallback.onTimeShiftCurrentPositionChanged(timeMs);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifyTimeShiftCurrentPositionChanged", e);
                    }
                }
            });
        }

        /**
         * Assigns a size and position to the surface passed in {@link #onSetSurface}. The position
         * is relative to the overlay view that sits on top of this surface.
         *
         * @param left Left position in pixels, relative to the overlay view.
         * @param top Top position in pixels, relative to the overlay view.
         * @param right Right position in pixels, relative to the overlay view.
         * @param bottom Bottom position in pixels, relative to the overlay view.
         * @see #onOverlayViewSizeChanged
         */
        public void layoutSurface(final int left, final int top, final int right,
                final int bottom) {
            if (left > right || top > bottom) {
                throw new IllegalArgumentException("Invalid parameter");
            }
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "layoutSurface (l=" + left + ", t=" + top + ", r="
                                + right + ", b=" + bottom + ",)");
                        if (mSessionCallback != null) {
                            mSessionCallback.onLayoutSurface(left, top, right, bottom);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in layoutSurface", e);
                    }
                }
            });
        }

        /**
         * Called when the session is released.
         */
        public abstract void onRelease();

        /**
         * Sets the current session as the main session. The main session is a session whose
         * corresponding TV input determines the HDMI-CEC active source device.
         *
         * <p>TV input service that manages HDMI-CEC logical device should implement {@link
         * #onSetMain} to (1) select the corresponding HDMI logical device as the source device
         * when {@code isMain} is {@code true}, and to (2) select the internal device (= TV itself)
         * as the source device when {@code isMain} is {@code false} and the session is still main.
         * Also, if a surface is passed to a non-main session and active source is changed to
         * initiate the surface, the active source should be returned to the main session.
         *
         * <p>{@link TvView} guarantees that, when tuning involves a session transition, {@code
         * onSetMain(true)} for new session is called first, {@code onSetMain(false)} for old
         * session is called afterwards. This allows {@code onSetMain(false)} to be no-op when TV
         * input service knows that the next main session corresponds to another HDMI logical
         * device. Practically, this implies that one TV input service should handle all HDMI port
         * and HDMI-CEC logical devices for smooth active source transition.
         *
         * @param isMain If true, session should become main.
         * @see TvView#setMain
         * @hide
         */
        @SystemApi
        public void onSetMain(boolean isMain) {
        }

        /**
         * Called when the application sets the surface.
         *
         * <p>The TV input service should render video onto the given surface. When called with
         * {@code null}, the input service should immediately free any references to the
         * currently set surface and stop using it.
         *
         * @param surface The surface to be used for video rendering. Can be {@code null}.
         * @return {@code true} if the surface was set successfully, {@code false} otherwise.
         */
        public abstract boolean onSetSurface(@Nullable Surface surface);

        /**
         * Called after any structural changes (format or size) have been made to the surface passed
         * in {@link #onSetSurface}. This method is always called at least once, after
         * {@link #onSetSurface} is called with non-null surface.
         *
         * @param format The new PixelFormat of the surface.
         * @param width The new width of the surface.
         * @param height The new height of the surface.
         */
        public void onSurfaceChanged(int format, int width, int height) {
        }

        /**
         * Called when the size of the overlay view is changed by the application.
         *
         * <p>This is always called at least once when the session is created regardless of whether
         * the overlay view is enabled or not. The overlay view size is the same as the containing
         * {@link TvView}. Note that the size of the underlying surface can be different if the
         * surface was changed by calling {@link #layoutSurface}.
         *
         * @param width The width of the overlay view.
         * @param height The height of the overlay view.
         */
        public void onOverlayViewSizeChanged(int width, int height) {
        }

        /**
         * Sets the relative stream volume of the current TV input session.
         *
         * <p>The implementation should honor this request in order to handle audio focus changes or
         * mute the current session when multiple sessions, possibly from different inputs are
         * active. If the method has not yet been called, the implementation should assume the
         * default value of {@code 1.0f}.
         *
         * @param volume A volume value between {@code 0.0f} to {@code 1.0f}.
         */
        public abstract void onSetStreamVolume(@FloatRange(from = 0.0, to = 1.0) float volume);

        /**
         * Tunes to a given channel.
         *
         * <p>No video will be displayed until {@link #notifyVideoAvailable()} is called.
         * Also, {@link #notifyVideoUnavailable(int)} should be called when the TV input cannot
         * continue playing the given channel.
         *
         * @param channelUri The URI of the channel.
         * @return {@code true} if the tuning was successful, {@code false} otherwise.
         */
        public abstract boolean onTune(Uri channelUri);

        /**
         * Tunes to a given channel. Override this method in order to handle domain-specific
         * features that are only known between certain TV inputs and their clients.
         *
         * <p>The default implementation calls {@link #onTune(Uri)}.
         *
         * @param channelUri The URI of the channel.
         * @param params Domain-specific data for this tune request. Keys <em>must</em> be a scoped
         *            name, i.e. prefixed with a package name you own, so that different developers
         *            will not create conflicting keys.
         * @return {@code true} if the tuning was successful, {@code false} otherwise.
         */
        public boolean onTune(Uri channelUri, Bundle params) {
            return onTune(channelUri);
        }

        /**
         * Enables or disables the caption.
         *
         * <p>The locale for the user's preferred captioning language can be obtained by calling
         * {@link CaptioningManager#getLocale CaptioningManager.getLocale()}.
         *
         * @param enabled {@code true} to enable, {@code false} to disable.
         * @see CaptioningManager
         */
        public abstract void onSetCaptionEnabled(boolean enabled);

        /**
         * Requests to unblock the content according to the given rating.
         *
         * <p>The implementation should unblock the content.
         * TV input service has responsibility to decide when/how the unblock expires
         * while it can keep previously unblocked ratings in order not to ask a user
         * to unblock whenever a content rating is changed.
         * Therefore an unblocked rating can be valid for a channel, a program,
         * or certain amount of time depending on the implementation.
         *
         * @param unblockedRating An unblocked content rating
         */
        public void onUnblockContent(TvContentRating unblockedRating) {
        }

        /**
         * Selects a given track.
         *
         * <p>If this is done successfully, the implementation should call
         * {@link #notifyTrackSelected} to help applications maintain the up-to-date list of the
         * selected tracks.
         *
         * @param trackId The ID of the track to select. {@code null} means to unselect the current
         *            track for a given type.
         * @param type The type of the track to select. The type can be
         *            {@link TvTrackInfo#TYPE_AUDIO}, {@link TvTrackInfo#TYPE_VIDEO} or
         *            {@link TvTrackInfo#TYPE_SUBTITLE}.
         * @return {@code true} if the track selection was successful, {@code false} otherwise.
         * @see #notifyTrackSelected
         */
        public boolean onSelectTrack(int type, @Nullable String trackId) {
            return false;
        }

        /**
         * Processes a private command sent from the application to the TV input. This can be used
         * to provide domain-specific features that are only known between certain TV inputs and
         * their clients.
         *
         * @param action Name of the command to be performed. This <em>must</em> be a scoped name,
         *            i.e. prefixed with a package name you own, so that different developers will
         *            not create conflicting commands.
         * @param data Any data to include with the command.
         */
        public void onAppPrivateCommand(@NonNull String action, Bundle data) {
        }

        /**
         * Called when the application requests to create an overlay view. Each session
         * implementation can override this method and return its own view.
         *
         * @return a view attached to the overlay window
         */
        public View onCreateOverlayView() {
            return null;
        }

        /**
         * Called when the application requests to play a given recorded TV program.
         *
         * @param recordedProgramUri The URI of a recorded TV program.
         * @see #onTimeShiftResume()
         * @see #onTimeShiftPause()
         * @see #onTimeShiftSeekTo(long)
         * @see #onTimeShiftSetPlaybackParams(PlaybackParams)
         * @see #onTimeShiftGetStartPosition()
         * @see #onTimeShiftGetCurrentPosition()
         */
        public void onTimeShiftPlay(Uri recordedProgramUri) {
        }

        /**
         * Called when the application requests to pause playback.
         *
         * @see #onTimeShiftPlay(Uri)
         * @see #onTimeShiftResume()
         * @see #onTimeShiftSeekTo(long)
         * @see #onTimeShiftSetPlaybackParams(PlaybackParams)
         * @see #onTimeShiftGetStartPosition()
         * @see #onTimeShiftGetCurrentPosition()
         */
        public void onTimeShiftPause() {
        }

        /**
         * Called when the application requests to resume playback.
         *
         * @see #onTimeShiftPlay(Uri)
         * @see #onTimeShiftPause()
         * @see #onTimeShiftSeekTo(long)
         * @see #onTimeShiftSetPlaybackParams(PlaybackParams)
         * @see #onTimeShiftGetStartPosition()
         * @see #onTimeShiftGetCurrentPosition()
         */
        public void onTimeShiftResume() {
        }

        /**
         * Called when the application requests to seek to a specified time position. Normally, the
         * position is given within range between the start and the current time, inclusively. The
         * implementation is expected to seek to the nearest time position if the given position is
         * not in the range.
         *
         * @param timeMs The time position to seek to, in milliseconds since the epoch.
         * @see #onTimeShiftPlay(Uri)
         * @see #onTimeShiftResume()
         * @see #onTimeShiftPause()
         * @see #onTimeShiftSetPlaybackParams(PlaybackParams)
         * @see #onTimeShiftGetStartPosition()
         * @see #onTimeShiftGetCurrentPosition()
         */
        public void onTimeShiftSeekTo(long timeMs) {
        }

        /**
         * Called when the application sets playback parameters containing the speed and audio mode.
         *
         * <p>Once the playback parameters are set, the implementation should honor the current
         * settings until the next tune request. Pause/resume/seek request does not reset the
         * parameters previously set.
         *
         * @param params The playback params.
         * @see #onTimeShiftPlay(Uri)
         * @see #onTimeShiftResume()
         * @see #onTimeShiftPause()
         * @see #onTimeShiftSeekTo(long)
         * @see #onTimeShiftGetStartPosition()
         * @see #onTimeShiftGetCurrentPosition()
         */
        public void onTimeShiftSetPlaybackParams(PlaybackParams params) {
        }

        /**
         * Returns the start position for time shifting, in milliseconds since the epoch.
         * Returns {@link TvInputManager#TIME_SHIFT_INVALID_TIME} if the position is unknown at the
         * moment.
         *
         * <p>The start position for time shifting indicates the earliest possible time the user can
         * seek to. Initially this is equivalent to the time when the implementation starts
         * recording. Later it may be adjusted because there is insufficient space or the duration
         * of recording is limited by the implementation. The application does not allow the user to
         * seek to a position earlier than the start position.
         *
         * <p>For playback of a recorded program initiated by {@link #onTimeShiftPlay(Uri)}, the
         * start position should be 0 and does not change.
         *
         * @see #onTimeShiftPlay(Uri)
         * @see #onTimeShiftResume()
         * @see #onTimeShiftPause()
         * @see #onTimeShiftSeekTo(long)
         * @see #onTimeShiftSetPlaybackParams(PlaybackParams)
         * @see #onTimeShiftGetCurrentPosition()
         */
        public long onTimeShiftGetStartPosition() {
            return TvInputManager.TIME_SHIFT_INVALID_TIME;
        }

        /**
         * Returns the current position for time shifting, in milliseconds since the epoch.
         * Returns {@link TvInputManager#TIME_SHIFT_INVALID_TIME} if the position is unknown at the
         * moment.
         *
         * <p>The current position for time shifting is the same as the current position of
         * playback. It should be equal to or greater than the start position reported by
         * {@link #onTimeShiftGetStartPosition()}. When playback is completed, the current position
         * should stay where the playback ends, in other words, the returned value of this mehtod
         * should be equal to the start position plus the duration of the program.
         *
         * @see #onTimeShiftPlay(Uri)
         * @see #onTimeShiftResume()
         * @see #onTimeShiftPause()
         * @see #onTimeShiftSeekTo(long)
         * @see #onTimeShiftSetPlaybackParams(PlaybackParams)
         * @see #onTimeShiftGetStartPosition()
         */
        public long onTimeShiftGetCurrentPosition() {
            return TvInputManager.TIME_SHIFT_INVALID_TIME;
        }

        /**
         * Default implementation of {@link android.view.KeyEvent.Callback#onKeyDown(int, KeyEvent)
         * KeyEvent.Callback.onKeyDown()}: always returns false (doesn't handle the event).
         *
         * <p>Override this to intercept key down events before they are processed by the
         * application. If you return true, the application will not process the event itself. If
         * you return false, the normal application processing will occur as if the TV input had not
         * seen the event at all.
         *
         * @param keyCode The value in event.getKeyCode().
         * @param event Description of the key event.
         * @return If you handled the event, return {@code true}. If you want to allow the event to
         *         be handled by the next receiver, return {@code false}.
         */
        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            return false;
        }

        /**
         * Default implementation of
         * {@link android.view.KeyEvent.Callback#onKeyLongPress(int, KeyEvent)
         * KeyEvent.Callback.onKeyLongPress()}: always returns false (doesn't handle the event).
         *
         * <p>Override this to intercept key long press events before they are processed by the
         * application. If you return true, the application will not process the event itself. If
         * you return false, the normal application processing will occur as if the TV input had not
         * seen the event at all.
         *
         * @param keyCode The value in event.getKeyCode().
         * @param event Description of the key event.
         * @return If you handled the event, return {@code true}. If you want to allow the event to
         *         be handled by the next receiver, return {@code false}.
         */
        @Override
        public boolean onKeyLongPress(int keyCode, KeyEvent event) {
            return false;
        }

        /**
         * Default implementation of
         * {@link android.view.KeyEvent.Callback#onKeyMultiple(int, int, KeyEvent)
         * KeyEvent.Callback.onKeyMultiple()}: always returns false (doesn't handle the event).
         *
         * <p>Override this to intercept special key multiple events before they are processed by
         * the application. If you return true, the application will not itself process the event.
         * If you return false, the normal application processing will occur as if the TV input had
         * not seen the event at all.
         *
         * @param keyCode The value in event.getKeyCode().
         * @param count The number of times the action was made.
         * @param event Description of the key event.
         * @return If you handled the event, return {@code true}. If you want to allow the event to
         *         be handled by the next receiver, return {@code false}.
         */
        @Override
        public boolean onKeyMultiple(int keyCode, int count, KeyEvent event) {
            return false;
        }

        /**
         * Default implementation of {@link android.view.KeyEvent.Callback#onKeyUp(int, KeyEvent)
         * KeyEvent.Callback.onKeyUp()}: always returns false (doesn't handle the event).
         *
         * <p>Override this to intercept key up events before they are processed by the application.
         * If you return true, the application will not itself process the event. If you return false,
         * the normal application processing will occur as if the TV input had not seen the event at
         * all.
         *
         * @param keyCode The value in event.getKeyCode().
         * @param event Description of the key event.
         * @return If you handled the event, return {@code true}. If you want to allow the event to
         *         be handled by the next receiver, return {@code false}.
         */
        @Override
        public boolean onKeyUp(int keyCode, KeyEvent event) {
            return false;
        }

        /**
         * Implement this method to handle touch screen motion events on the current input session.
         *
         * @param event The motion event being received.
         * @return If you handled the event, return {@code true}. If you want to allow the event to
         *         be handled by the next receiver, return {@code false}.
         * @see View#onTouchEvent
         */
        public boolean onTouchEvent(MotionEvent event) {
            return false;
        }

        /**
         * Implement this method to handle trackball events on the current input session.
         *
         * @param event The motion event being received.
         * @return If you handled the event, return {@code true}. If you want to allow the event to
         *         be handled by the next receiver, return {@code false}.
         * @see View#onTrackballEvent
         */
        public boolean onTrackballEvent(MotionEvent event) {
            return false;
        }

        /**
         * Implement this method to handle generic motion events on the current input session.
         *
         * @param event The motion event being received.
         * @return If you handled the event, return {@code true}. If you want to allow the event to
         *         be handled by the next receiver, return {@code false}.
         * @see View#onGenericMotionEvent
         */
        public boolean onGenericMotionEvent(MotionEvent event) {
            return false;
        }

        /**
         * This method is called when the application would like to stop using the current input
         * session.
         */
        void release() {
            onRelease();
            if (mSurface != null) {
                mSurface.release();
                mSurface = null;
            }
            synchronized(mLock) {
                mSessionCallback = null;
                mPendingActions.clear();
            }
            // Removes the overlay view lastly so that any hanging on the main thread can be handled
            // in {@link #scheduleOverlayViewCleanup}.
            removeOverlayView(true);
            mHandler.removeCallbacks(mTimeShiftPositionTrackingRunnable);
        }

        /**
         * Calls {@link #onSetMain}.
         */
        void setMain(boolean isMain) {
            onSetMain(isMain);
        }

        /**
         * Calls {@link #onSetSurface}.
         */
        void setSurface(Surface surface) {
            onSetSurface(surface);
            if (mSurface != null) {
                mSurface.release();
            }
            mSurface = surface;
            // TODO: Handle failure.
        }

        /**
         * Calls {@link #onSurfaceChanged}.
         */
        void dispatchSurfaceChanged(int format, int width, int height) {
            if (DEBUG) {
                Log.d(TAG, "dispatchSurfaceChanged(format=" + format + ", width=" + width
                        + ", height=" + height + ")");
            }
            onSurfaceChanged(format, width, height);
        }

        /**
         * Calls {@link #onSetStreamVolume}.
         */
        void setStreamVolume(float volume) {
            onSetStreamVolume(volume);
        }

        /**
         * Calls {@link #onTune(Uri, Bundle)}.
         */
        void tune(Uri channelUri, Bundle params) {
            mCurrentPositionMs = TvInputManager.TIME_SHIFT_INVALID_TIME;
            onTune(channelUri, params);
            // TODO: Handle failure.
        }

        /**
         * Calls {@link #onSetCaptionEnabled}.
         */
        void setCaptionEnabled(boolean enabled) {
            onSetCaptionEnabled(enabled);
        }

        /**
         * Calls {@link #onSelectTrack}.
         */
        void selectTrack(int type, String trackId) {
            onSelectTrack(type, trackId);
        }

        /**
         * Calls {@link #onUnblockContent}.
         */
        void unblockContent(String unblockedRating) {
            onUnblockContent(TvContentRating.unflattenFromString(unblockedRating));
            // TODO: Handle failure.
        }

        /**
         * Calls {@link #onAppPrivateCommand}.
         */
        void appPrivateCommand(String action, Bundle data) {
            onAppPrivateCommand(action, data);
        }

        /**
         * Creates an overlay view. This calls {@link #onCreateOverlayView} to get a view to attach
         * to the overlay window.
         *
         * @param windowToken A window token of the application.
         * @param frame A position of the overlay view.
         */
        void createOverlayView(IBinder windowToken, Rect frame) {
            if (mOverlayViewContainer != null) {
                removeOverlayView(false);
            }
            if (DEBUG) Log.d(TAG, "create overlay view(" + frame + ")");
            mWindowToken = windowToken;
            mOverlayFrame = frame;
            onOverlayViewSizeChanged(frame.right - frame.left, frame.bottom - frame.top);
            if (!mOverlayViewEnabled) {
                return;
            }
            mOverlayView = onCreateOverlayView();
            if (mOverlayView == null) {
                return;
            }
            if (mOverlayViewCleanUpTask != null) {
                mOverlayViewCleanUpTask.cancel(true);
                mOverlayViewCleanUpTask = null;
            }
            // Creates a container view to check hanging on the overlay view detaching.
            // Adding/removing the overlay view to/from the container make the view attach/detach
            // logic run on the main thread.
            mOverlayViewContainer = new FrameLayout(mContext.getApplicationContext());
            mOverlayViewContainer.addView(mOverlayView);
            // TvView's window type is TYPE_APPLICATION_MEDIA and we want to create
            // an overlay window above the media window but below the application window.
            int type = WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY;
            // We make the overlay view non-focusable and non-touchable so that
            // the application that owns the window token can decide whether to consume or
            // dispatch the input events.
            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            if (ActivityManager.isHighEndGfx()) {
                flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            }
            mWindowParams = new WindowManager.LayoutParams(
                    frame.right - frame.left, frame.bottom - frame.top,
                    frame.left, frame.top, type, flags, PixelFormat.TRANSPARENT);
            mWindowParams.privateFlags |=
                    WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
            mWindowParams.gravity = Gravity.START | Gravity.TOP;
            mWindowParams.token = windowToken;
            mWindowManager.addView(mOverlayViewContainer, mWindowParams);
        }

        /**
         * Relayouts the current overlay view.
         *
         * @param frame A new position of the overlay view.
         */
        void relayoutOverlayView(Rect frame) {
            if (DEBUG) Log.d(TAG, "relayoutOverlayView(" + frame + ")");
            if (mOverlayFrame == null || mOverlayFrame.width() != frame.width()
                    || mOverlayFrame.height() != frame.height()) {
                // Note: relayoutOverlayView is called whenever TvView's layout is changed
                // regardless of setOverlayViewEnabled.
                onOverlayViewSizeChanged(frame.right - frame.left, frame.bottom - frame.top);
            }
            mOverlayFrame = frame;
            if (!mOverlayViewEnabled || mOverlayViewContainer == null) {
                return;
            }
            mWindowParams.x = frame.left;
            mWindowParams.y = frame.top;
            mWindowParams.width = frame.right - frame.left;
            mWindowParams.height = frame.bottom - frame.top;
            mWindowManager.updateViewLayout(mOverlayViewContainer, mWindowParams);
        }

        /**
         * Removes the current overlay view.
         */
        void removeOverlayView(boolean clearWindowToken) {
            if (DEBUG) Log.d(TAG, "removeOverlayView(" + mOverlayViewContainer + ")");
            if (clearWindowToken) {
                mWindowToken = null;
                mOverlayFrame = null;
            }
            if (mOverlayViewContainer != null) {
                // Removes the overlay view from the view hierarchy in advance so that it can be
                // cleaned up in the {@link OverlayViewCleanUpTask} if the remove process is
                // hanging.
                mOverlayViewContainer.removeView(mOverlayView);
                mOverlayView = null;
                mWindowManager.removeView(mOverlayViewContainer);
                mOverlayViewContainer = null;
                mWindowParams = null;
            }
        }

        /**
         * Calls {@link #onTimeShiftPlay(Uri)}.
         */
        void timeShiftPlay(Uri recordedProgramUri) {
            mCurrentPositionMs = 0;
            onTimeShiftPlay(recordedProgramUri);
        }

        /**
         * Calls {@link #onTimeShiftPause}.
         */
        void timeShiftPause() {
            onTimeShiftPause();
        }

        /**
         * Calls {@link #onTimeShiftResume}.
         */
        void timeShiftResume() {
            onTimeShiftResume();
        }

        /**
         * Calls {@link #onTimeShiftSeekTo}.
         */
        void timeShiftSeekTo(long timeMs) {
            onTimeShiftSeekTo(timeMs);
        }

        /**
         * Calls {@link #onTimeShiftSetPlaybackParams}.
         */
        void timeShiftSetPlaybackParams(PlaybackParams params) {
            onTimeShiftSetPlaybackParams(params);
        }

        /**
         * Enable/disable position tracking.
         *
         * @param enable {@code true} to enable tracking, {@code false} otherwise.
         */
        void timeShiftEnablePositionTracking(boolean enable) {
            if (enable) {
                mHandler.post(mTimeShiftPositionTrackingRunnable);
            } else {
                mHandler.removeCallbacks(mTimeShiftPositionTrackingRunnable);
                mStartPositionMs = TvInputManager.TIME_SHIFT_INVALID_TIME;
                mCurrentPositionMs = TvInputManager.TIME_SHIFT_INVALID_TIME;
            }
        }

        /**
         * Schedules a task which checks whether the overlay view is detached and kills the process
         * if it is not. Note that this method is expected to be called in a non-main thread.
         */
        void scheduleOverlayViewCleanup() {
            View overlayViewParent = mOverlayViewContainer;
            if (overlayViewParent != null) {
                mOverlayViewCleanUpTask = new OverlayViewCleanUpTask();
                mOverlayViewCleanUpTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR,
                        overlayViewParent);
            }
        }

        /**
         * Takes care of dispatching incoming input events and tells whether the event was handled.
         */
        int dispatchInputEvent(InputEvent event, InputEventReceiver receiver) {
            if (DEBUG) Log.d(TAG, "dispatchInputEvent(" + event + ")");
            boolean isNavigationKey = false;
            boolean skipDispatchToOverlayView = false;
            if (event instanceof KeyEvent) {
                KeyEvent keyEvent = (KeyEvent) event;
                if (keyEvent.dispatch(this, mDispatcherState, this)) {
                    return TvInputManager.Session.DISPATCH_HANDLED;
                }
                isNavigationKey = isNavigationKey(keyEvent.getKeyCode());
                // When media keys and KEYCODE_MEDIA_AUDIO_TRACK are dispatched to ViewRootImpl,
                // ViewRootImpl always consumes the keys. In this case, the application loses
                // a chance to handle media keys. Therefore, media keys are not dispatched to
                // ViewRootImpl.
                skipDispatchToOverlayView = KeyEvent.isMediaKey(keyEvent.getKeyCode())
                        || keyEvent.getKeyCode() == KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK;
            } else if (event instanceof MotionEvent) {
                MotionEvent motionEvent = (MotionEvent) event;
                final int source = motionEvent.getSource();
                if (motionEvent.isTouchEvent()) {
                    if (onTouchEvent(motionEvent)) {
                        return TvInputManager.Session.DISPATCH_HANDLED;
                    }
                } else if ((source & InputDevice.SOURCE_CLASS_TRACKBALL) != 0) {
                    if (onTrackballEvent(motionEvent)) {
                        return TvInputManager.Session.DISPATCH_HANDLED;
                    }
                } else {
                    if (onGenericMotionEvent(motionEvent)) {
                        return TvInputManager.Session.DISPATCH_HANDLED;
                    }
                }
            }
            if (mOverlayViewContainer == null || !mOverlayViewContainer.isAttachedToWindow()
                    || skipDispatchToOverlayView) {
                return TvInputManager.Session.DISPATCH_NOT_HANDLED;
            }
            if (!mOverlayViewContainer.hasWindowFocus()) {
                mOverlayViewContainer.getViewRootImpl().windowFocusChanged(true, true);
            }
            if (isNavigationKey && mOverlayViewContainer.hasFocusable()) {
                // If mOverlayView has focusable views, navigation key events should be always
                // handled. If not, it can make the application UI navigation messed up.
                // For example, in the case that the left-most view is focused, a left key event
                // will not be handled in ViewRootImpl. Then, the left key event will be handled in
                // the application during the UI navigation of the TV input.
                mOverlayViewContainer.getViewRootImpl().dispatchInputEvent(event);
                return TvInputManager.Session.DISPATCH_HANDLED;
            } else {
                mOverlayViewContainer.getViewRootImpl().dispatchInputEvent(event, receiver);
                return TvInputManager.Session.DISPATCH_IN_PROGRESS;
            }
        }

        private void initialize(ITvInputSessionCallback callback) {
            synchronized(mLock) {
                mSessionCallback = callback;
                for (Runnable runnable : mPendingActions) {
                    runnable.run();
                }
                mPendingActions.clear();
            }
        }

        private void executeOrPostRunnableOnMainThread(Runnable action) {
            synchronized(mLock) {
                if (mSessionCallback == null) {
                    // The session is not initialized yet.
                    mPendingActions.add(action);
                } else {
                    if (mHandler.getLooper().isCurrentThread()) {
                        action.run();
                    } else {
                        // Posts the runnable if this is not called from the main thread
                        mHandler.post(action);
                    }
                }
            }
        }

        private final class TimeShiftPositionTrackingRunnable implements Runnable {
            @Override
            public void run() {
                long startPositionMs = onTimeShiftGetStartPosition();
                if (mStartPositionMs == TvInputManager.TIME_SHIFT_INVALID_TIME
                        || mStartPositionMs != startPositionMs) {
                    mStartPositionMs = startPositionMs;
                    notifyTimeShiftStartPositionChanged(startPositionMs);
                }
                long currentPositionMs = onTimeShiftGetCurrentPosition();
                if (currentPositionMs < mStartPositionMs) {
                    Log.w(TAG, "Current position (" + currentPositionMs + ") cannot be earlier than"
                            + " start position (" + mStartPositionMs + "). Reset to the start "
                            + "position.");
                    currentPositionMs = mStartPositionMs;
                }
                if (mCurrentPositionMs == TvInputManager.TIME_SHIFT_INVALID_TIME
                        || mCurrentPositionMs != currentPositionMs) {
                    mCurrentPositionMs = currentPositionMs;
                    notifyTimeShiftCurrentPositionChanged(currentPositionMs);
                }
                mHandler.removeCallbacks(mTimeShiftPositionTrackingRunnable);
                mHandler.postDelayed(mTimeShiftPositionTrackingRunnable,
                        POSITION_UPDATE_INTERVAL_MS);
            }
        }
    }

    private static final class OverlayViewCleanUpTask extends AsyncTask<View, Void, Void> {
        @Override
        protected Void doInBackground(View... views) {
            View overlayViewParent = views[0];
            try {
                Thread.sleep(DETACH_OVERLAY_VIEW_TIMEOUT_MS);
            } catch (InterruptedException e) {
                return null;
            }
            if (isCancelled()) {
                return null;
            }
            if (overlayViewParent.isAttachedToWindow()) {
                Log.e(TAG, "Time out on releasing overlay view. Killing "
                        + overlayViewParent.getContext().getPackageName());
                Process.killProcess(Process.myPid());
            }
            return null;
        }
    }

    /**
     * Base class for derived classes to implement to provide a TV input recording session.
     */
    public abstract static class RecordingSession {
        final Handler mHandler;

        private final Object mLock = new Object();
        // @GuardedBy("mLock")
        private ITvInputSessionCallback mSessionCallback;
        // @GuardedBy("mLock")
        private final List<Runnable> mPendingActions = new ArrayList<>();

        /**
         * Creates a new RecordingSession.
         *
         * @param context The context of the application
         */
        public RecordingSession(Context context) {
            mHandler = new Handler(context.getMainLooper());
        }

        /**
         * Informs the application that this recording session has been tuned to the given channel
         * and is ready to start recording.
         *
         * <p>Upon receiving a call to {@link #onTune(Uri)}, the session is expected to tune to the
         * passed channel and call this method to indicate that it is now available for immediate
         * recording. When {@link #onStartRecording(Uri)} is called, recording must start with
         * minimal delay.
         *
         * @param channelUri The URI of a channel.
         */
        public void notifyTuned(Uri channelUri) {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "notifyTuned");
                        if (mSessionCallback != null) {
                            mSessionCallback.onTuned(channelUri);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifyTuned", e);
                    }
                }
            });
        }

        /**
         * Informs the application that this recording session has stopped recording and created a
         * new data entry in the {@link TvContract.RecordedPrograms} table that describes the newly
         * recorded program.
         *
         * <p>The recording session must call this method in response to {@link #onStopRecording()}.
         * The session may call it even before receiving a call to {@link #onStopRecording()} if a
         * partially recorded program is available when there is an error.
         *
         * @param recordedProgramUri The URI of the newly recorded program.
         */
        public void notifyRecordingStopped(final Uri recordedProgramUri) {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "notifyRecordingStopped");
                        if (mSessionCallback != null) {
                            mSessionCallback.onRecordingStopped(recordedProgramUri);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifyRecordingStopped", e);
                    }
                }
            });
        }

        /**
         * Informs the application that there is an error and this recording session is no longer
         * able to start or continue recording. It may be called at any time after the recording
         * session is created until {@link #onRelease()} is called.
         *
         * <p>The application may release the current session upon receiving the error code through
         * {@link TvRecordingClient.RecordingCallback#onError(int)}. The session may call
         * {@link #notifyRecordingStopped(Uri)} if a partially recorded but still playable program
         * is available, before calling this method.
         *
         * @param error The error code. Should be one of the followings.
         * <ul>
         * <li>{@link TvInputManager#RECORDING_ERROR_UNKNOWN}
         * <li>{@link TvInputManager#RECORDING_ERROR_INSUFFICIENT_SPACE}
         * <li>{@link TvInputManager#RECORDING_ERROR_RESOURCE_BUSY}
         * </ul>
         */
        public void notifyError(@TvInputManager.RecordingError int error) {
            if (error < TvInputManager.RECORDING_ERROR_START
                    || error > TvInputManager.RECORDING_ERROR_END) {
                Log.w(TAG, "notifyError - invalid error code (" + error
                        + ") is changed to RECORDING_ERROR_UNKNOWN.");
                error = TvInputManager.RECORDING_ERROR_UNKNOWN;
            }
            final int validError = error;
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "notifyError");
                        if (mSessionCallback != null) {
                            mSessionCallback.onError(validError);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifyError", e);
                    }
                }
            });
        }

        /**
         * Dispatches an event to the application using this recording session.
         *
         * @param eventType The type of the event.
         * @param eventArgs Optional arguments of the event.
         * @hide
         */
        @SystemApi
        public void notifySessionEvent(@NonNull final String eventType, final Bundle eventArgs) {
            Preconditions.checkNotNull(eventType);
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "notifySessionEvent(" + eventType + ")");
                        if (mSessionCallback != null) {
                            mSessionCallback.onSessionEvent(eventType, eventArgs);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in sending event (event=" + eventType + ")", e);
                    }
                }
            });
        }

        /**
         * Called when the application requests to tune to a given channel for TV program recording.
         *
         * <p>The application may call this method before starting or after stopping recording, but
         * not during recording.
         *
         * <p>The session must call {@link #notifyTuned(Uri)} if the tune request was fulfilled, or
         * {@link #notifyError(int)} otherwise.
         *
         * @param channelUri The URI of a channel.
         */
        public abstract void onTune(Uri channelUri);

        /**
         * Called when the application requests to tune to a given channel for TV program recording.
         * Override this method in order to handle domain-specific features that are only known
         * between certain TV inputs and their clients.
         *
         * <p>The application may call this method before starting or after stopping recording, but
         * not during recording. The default implementation calls {@link #onTune(Uri)}.
         *
         * <p>The session must call {@link #notifyTuned(Uri)} if the tune request was fulfilled, or
         * {@link #notifyError(int)} otherwise.
         *
         * @param channelUri The URI of a channel.
         * @param params Domain-specific data for this tune request. Keys <em>must</em> be a scoped
         *            name, i.e. prefixed with a package name you own, so that different developers
         *            will not create conflicting keys.
         */
        public void onTune(Uri channelUri, Bundle params) {
            onTune(channelUri);
        }

        /**
         * Called when the application requests to start TV program recording. Recording must start
         * immediately when this method is called.
         *
         * <p>The application may supply the URI for a TV program for filling in program specific
         * data fields in the {@link android.media.tv.TvContract.RecordedPrograms} table.
         * A non-null {@code programUri} implies the started recording should be of that specific
         * program, whereas null {@code programUri} does not impose such a requirement and the
         * recording can span across multiple TV programs. In either case, the application must call
         * {@link TvRecordingClient#stopRecording()} to stop the recording.
         *
         * <p>The session must call {@link #notifyError(int)} if the start request cannot be
         * fulfilled.
         *
         * @param programUri The URI for the TV program to record, built by
         *            {@link TvContract#buildProgramUri(long)}. Can be {@code null}.
         */
        public abstract void onStartRecording(@Nullable Uri programUri);

        /**
         * Called when the application requests to stop TV program recording. Recording must stop
         * immediately when this method is called.
         *
         * <p>The session must create a new data entry in the
         * {@link android.media.tv.TvContract.RecordedPrograms} table that describes the newly
         * recorded program and call {@link #notifyRecordingStopped(Uri)} with the URI to that
         * entry.
         * If the stop request cannot be fulfilled, the session must call {@link #notifyError(int)}.
         *
         */
        public abstract void onStopRecording();


        /**
         * Called when the application requests to release all the resources held by this recording
         * session.
         */
        public abstract void onRelease();

        /**
         * Processes a private command sent from the application to the TV input. This can be used
         * to provide domain-specific features that are only known between certain TV inputs and
         * their clients.
         *
         * @param action Name of the command to be performed. This <em>must</em> be a scoped name,
         *            i.e. prefixed with a package name you own, so that different developers will
         *            not create conflicting commands.
         * @param data Any data to include with the command.
         */
        public void onAppPrivateCommand(@NonNull String action, Bundle data) {
        }

        /**
         * Calls {@link #onTune(Uri, Bundle)}.
         *
         */
        void tune(Uri channelUri, Bundle params) {
            onTune(channelUri, params);
        }

        /**
         * Calls {@link #onRelease()}.
         *
         */
        void release() {
            onRelease();
        }

        /**
         * Calls {@link #onStartRecording(Uri)}.
         *
         */
        void startRecording(@Nullable  Uri programUri) {
            onStartRecording(programUri);
        }

        /**
         * Calls {@link #onStopRecording()}.
         *
         */
        void stopRecording() {
            onStopRecording();
        }

        /**
         * Calls {@link #onAppPrivateCommand(String, Bundle)}.
         */
        void appPrivateCommand(String action, Bundle data) {
            onAppPrivateCommand(action, data);
        }

        private void initialize(ITvInputSessionCallback callback) {
            synchronized(mLock) {
                mSessionCallback = callback;
                for (Runnable runnable : mPendingActions) {
                    runnable.run();
                }
                mPendingActions.clear();
            }
        }

        private void executeOrPostRunnableOnMainThread(Runnable action) {
            synchronized(mLock) {
                if (mSessionCallback == null) {
                    // The session is not initialized yet.
                    mPendingActions.add(action);
                } else {
                    if (mHandler.getLooper().isCurrentThread()) {
                        action.run();
                    } else {
                        // Posts the runnable if this is not called from the main thread
                        mHandler.post(action);
                    }
                }
            }
        }
    }

    /**
     * Base class for a TV input session which represents an external device connected to a
     * hardware TV input.
     *
     * <p>This class is for an input which provides channels for the external set-top box to the
     * application. Once a TV input returns an implementation of this class on
     * {@link #onCreateSession(String)}, the framework will create a separate session for
     * a hardware TV Input (e.g. HDMI 1) and forward the application's surface to the session so
     * that the user can see the screen of the hardware TV Input when she tunes to a channel from
     * this TV input. The implementation of this class is expected to change the channel of the
     * external set-top box via a proprietary protocol when {@link HardwareSession#onTune} is
     * requested by the application.
     *
     * <p>Note that this class is not for inputs for internal hardware like built-in tuner and HDMI
     * 1.
     *
     * @see #onCreateSession(String)
     */
    public abstract static class HardwareSession extends Session {

        /**
         * Creates a new HardwareSession.
         *
         * @param context The context of the application
         */
        public HardwareSession(Context context) {
            super(context);
        }

        private TvInputManager.Session mHardwareSession;
        private ITvInputSession mProxySession;
        private ITvInputSessionCallback mProxySessionCallback;
        private Handler mServiceHandler;

        /**
         * Returns the hardware TV input ID the external device is connected to.
         *
         * <p>TV input is expected to provide {@link android.R.attr#setupActivity} so that
         * the application can launch it before using this TV input. The setup activity may let
         * the user select the hardware TV input to which the external device is connected. The ID
         * of the selected one should be stored in the TV input so that it can be returned here.
         */
        public abstract String getHardwareInputId();

        private final TvInputManager.SessionCallback mHardwareSessionCallback =
                new TvInputManager.SessionCallback() {
            @Override
            public void onSessionCreated(TvInputManager.Session session) {
                mHardwareSession = session;
                SomeArgs args = SomeArgs.obtain();
                if (session != null) {
                    args.arg1 = HardwareSession.this;
                    args.arg2 = mProxySession;
                    args.arg3 = mProxySessionCallback;
                    args.arg4 = session.getToken();
                    session.tune(TvContract.buildChannelUriForPassthroughInput(
                            getHardwareInputId()));
                } else {
                    args.arg1 = null;
                    args.arg2 = null;
                    args.arg3 = mProxySessionCallback;
                    args.arg4 = null;
                    onRelease();
                }
                mServiceHandler.obtainMessage(ServiceHandler.DO_NOTIFY_SESSION_CREATED, args)
                        .sendToTarget();
            }

            @Override
            public void onVideoAvailable(final TvInputManager.Session session) {
                if (mHardwareSession == session) {
                    onHardwareVideoAvailable();
                }
            }

            @Override
            public void onVideoUnavailable(final TvInputManager.Session session,
                    final int reason) {
                if (mHardwareSession == session) {
                    onHardwareVideoUnavailable(reason);
                }
            }
        };

        /**
         * This method will not be called in {@link HardwareSession}. Framework will
         * forward the application's surface to the hardware TV input.
         */
        @Override
        public final boolean onSetSurface(Surface surface) {
            Log.e(TAG, "onSetSurface() should not be called in HardwareProxySession.");
            return false;
        }

        /**
         * Called when the underlying hardware TV input session calls
         * {@link TvInputService.Session#notifyVideoAvailable()}.
         */
        public void onHardwareVideoAvailable() { }

        /**
         * Called when the underlying hardware TV input session calls
         * {@link TvInputService.Session#notifyVideoUnavailable(int)}.
         *
         * @param reason The reason that the hardware TV input stopped the playback:
         * <ul>
         * <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_UNKNOWN}
         * <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_TUNING}
         * <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL}
         * <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_BUFFERING}
         * <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY}
         * </ul>
         */
        public void onHardwareVideoUnavailable(int reason) { }

        @Override
        void release() {
            if (mHardwareSession != null) {
                mHardwareSession.release();
                mHardwareSession = null;
            }
            super.release();
        }
    }

    /** @hide */
    public static boolean isNavigationKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_MOVE_HOME:
            case KeyEvent.KEYCODE_MOVE_END:
            case KeyEvent.KEYCODE_TAB:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_ENTER:
                return true;
        }
        return false;
    }

    @SuppressLint("HandlerLeak")
    private final class ServiceHandler extends Handler {
        private static final int DO_CREATE_SESSION = 1;
        private static final int DO_NOTIFY_SESSION_CREATED = 2;
        private static final int DO_CREATE_RECORDING_SESSION = 3;
        private static final int DO_ADD_HARDWARE_INPUT = 4;
        private static final int DO_REMOVE_HARDWARE_INPUT = 5;
        private static final int DO_ADD_HDMI_INPUT = 6;
        private static final int DO_REMOVE_HDMI_INPUT = 7;

        private void broadcastAddHardwareInput(int deviceId, TvInputInfo inputInfo) {
            int n = mCallbacks.beginBroadcast();
            for (int i = 0; i < n; ++i) {
                try {
                    mCallbacks.getBroadcastItem(i).addHardwareInput(deviceId, inputInfo);
                } catch (RemoteException e) {
                    Log.e(TAG, "error in broadcastAddHardwareInput", e);
                }
            }
            mCallbacks.finishBroadcast();
        }

        private void broadcastAddHdmiInput(int id, TvInputInfo inputInfo) {
            int n = mCallbacks.beginBroadcast();
            for (int i = 0; i < n; ++i) {
                try {
                    mCallbacks.getBroadcastItem(i).addHdmiInput(id, inputInfo);
                } catch (RemoteException e) {
                    Log.e(TAG, "error in broadcastAddHdmiInput", e);
                }
            }
            mCallbacks.finishBroadcast();
        }

        private void broadcastRemoveHardwareInput(String inputId) {
            int n = mCallbacks.beginBroadcast();
            for (int i = 0; i < n; ++i) {
                try {
                    mCallbacks.getBroadcastItem(i).removeHardwareInput(inputId);
                } catch (RemoteException e) {
                    Log.e(TAG, "error in broadcastRemoveHardwareInput", e);
                }
            }
            mCallbacks.finishBroadcast();
        }

        @Override
        public final void handleMessage(Message msg) {
            switch (msg.what) {
                case DO_CREATE_SESSION: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    InputChannel channel = (InputChannel) args.arg1;
                    ITvInputSessionCallback cb = (ITvInputSessionCallback) args.arg2;
                    String inputId = (String) args.arg3;
                    args.recycle();
                    Session sessionImpl = onCreateSession(inputId);
                    if (sessionImpl == null) {
                        try {
                            // Failed to create a session.
                            cb.onSessionCreated(null, null);
                        } catch (RemoteException e) {
                            Log.e(TAG, "error in onSessionCreated", e);
                        }
                        return;
                    }
                    ITvInputSession stub = new ITvInputSessionWrapper(TvInputService.this,
                            sessionImpl, channel);
                    if (sessionImpl instanceof HardwareSession) {
                        HardwareSession proxySession =
                                ((HardwareSession) sessionImpl);
                        String hardwareInputId = proxySession.getHardwareInputId();
                        if (TextUtils.isEmpty(hardwareInputId) ||
                                !isPassthroughInput(hardwareInputId)) {
                            if (TextUtils.isEmpty(hardwareInputId)) {
                                Log.w(TAG, "Hardware input id is not setup yet.");
                            } else {
                                Log.w(TAG, "Invalid hardware input id : " + hardwareInputId);
                            }
                            sessionImpl.onRelease();
                            try {
                                cb.onSessionCreated(null, null);
                            } catch (RemoteException e) {
                                Log.e(TAG, "error in onSessionCreated", e);
                            }
                            return;
                        }
                        proxySession.mProxySession = stub;
                        proxySession.mProxySessionCallback = cb;
                        proxySession.mServiceHandler = mServiceHandler;
                        TvInputManager manager = (TvInputManager) getSystemService(
                                Context.TV_INPUT_SERVICE);
                        manager.createSession(hardwareInputId,
                                proxySession.mHardwareSessionCallback, mServiceHandler);
                    } else {
                        SomeArgs someArgs = SomeArgs.obtain();
                        someArgs.arg1 = sessionImpl;
                        someArgs.arg2 = stub;
                        someArgs.arg3 = cb;
                        someArgs.arg4 = null;
                        mServiceHandler.obtainMessage(ServiceHandler.DO_NOTIFY_SESSION_CREATED,
                                someArgs).sendToTarget();
                    }
                    return;
                }
                case DO_NOTIFY_SESSION_CREATED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Session sessionImpl = (Session) args.arg1;
                    ITvInputSession stub = (ITvInputSession) args.arg2;
                    ITvInputSessionCallback cb = (ITvInputSessionCallback) args.arg3;
                    IBinder hardwareSessionToken = (IBinder) args.arg4;
                    try {
                        cb.onSessionCreated(stub, hardwareSessionToken);
                    } catch (RemoteException e) {
                        Log.e(TAG, "error in onSessionCreated", e);
                    }
                    if (sessionImpl != null) {
                        sessionImpl.initialize(cb);
                    }
                    args.recycle();
                    return;
                }
                case DO_CREATE_RECORDING_SESSION: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    ITvInputSessionCallback cb = (ITvInputSessionCallback) args.arg1;
                    String inputId = (String) args.arg2;
                    args.recycle();
                    RecordingSession recordingSessionImpl = onCreateRecordingSession(inputId);
                    if (recordingSessionImpl == null) {
                        try {
                            // Failed to create a recording session.
                            cb.onSessionCreated(null, null);
                        } catch (RemoteException e) {
                            Log.e(TAG, "error in onSessionCreated", e);
                        }
                        return;
                    }
                    ITvInputSession stub = new ITvInputSessionWrapper(TvInputService.this,
                            recordingSessionImpl);
                    try {
                        cb.onSessionCreated(stub, null);
                    } catch (RemoteException e) {
                        Log.e(TAG, "error in onSessionCreated", e);
                    }
                    recordingSessionImpl.initialize(cb);
                    return;
                }
                case DO_ADD_HARDWARE_INPUT: {
                    TvInputHardwareInfo hardwareInfo = (TvInputHardwareInfo) msg.obj;
                    TvInputInfo inputInfo = onHardwareAdded(hardwareInfo);
                    if (inputInfo != null) {
                        broadcastAddHardwareInput(hardwareInfo.getDeviceId(), inputInfo);
                    }
                    return;
                }
                case DO_REMOVE_HARDWARE_INPUT: {
                    TvInputHardwareInfo hardwareInfo = (TvInputHardwareInfo) msg.obj;
                    String inputId = onHardwareRemoved(hardwareInfo);
                    if (inputId != null) {
                        broadcastRemoveHardwareInput(inputId);
                    }
                    return;
                }
                case DO_ADD_HDMI_INPUT: {
                    HdmiDeviceInfo deviceInfo = (HdmiDeviceInfo) msg.obj;
                    TvInputInfo inputInfo = onHdmiDeviceAdded(deviceInfo);
                    if (inputInfo != null) {
                        broadcastAddHdmiInput(deviceInfo.getId(), inputInfo);
                    }
                    return;
                }
                case DO_REMOVE_HDMI_INPUT: {
                    HdmiDeviceInfo deviceInfo = (HdmiDeviceInfo) msg.obj;
                    String inputId = onHdmiDeviceRemoved(deviceInfo);
                    if (inputId != null) {
                        broadcastRemoveHardwareInput(inputId);
                    }
                    return;
                }
                default: {
                    Log.w(TAG, "Unhandled message code: " + msg.what);
                    return;
                }
            }
        }
    }
}