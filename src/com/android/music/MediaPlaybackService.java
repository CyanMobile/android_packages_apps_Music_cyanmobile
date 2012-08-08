/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.music;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.preference.PreferenceManager;
import android.media.audiofx.AudioEffect;
import android.media.AudioManager;
import android.media.AudioManager.OnAudioFocusChangeListener;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.PowerManager.WakeLock;
import android.provider.BaseColumns;
import android.provider.MediaStore;
import android.provider.MediaStore.Audio.AudioColumns;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.Toast;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.Random;
import java.util.Vector;

import android.provider.Settings;

/**
 * Provides "background" audio playback capabilities, allowing the
 * user to switch between activities without stopping playback.
 */
public class MediaPlaybackService extends Service implements
		SensorEventListener, Shaker.Callback {
    /** used to specify whether enqueue() should start playing
     * the new list of files right away, next or once all the currently
     * queued files have been played
     */
    public static final int NOW = 1;
    public static final int NEXT = 2;
    public static final int LAST = 3;
    public static final int PLAYBACKSERVICE_STATUS = 1;
    
    public static final int SHUFFLE_NONE = 0;
    public static final int SHUFFLE_NORMAL = 1;
    public static final int SHUFFLE_AUTO = 2;
    
    public static final int REPEAT_NONE = 0;
    public static final int REPEAT_CURRENT = 1;
    public static final int REPEAT_ALL = 2;

    public static final String PLAYSTATE_CHANGED = "com.android.music.playstatechanged";
    public static final String META_CHANGED = "com.android.music.metachanged";
    public static final String QUEUE_CHANGED = "com.android.music.queuechanged";
    public static final String REPEATMODE_CHANGED = "com.android.music.repeatmodechanged";
    public static final String SHUFFLEMODE_CHANGED = "com.android.music.shufflemodechanged";

    public static final String ALARM_ALERT_ACTION = "com.android.deskclock.ALARM_ALERT";
    public static final String ALARM_DONE_ACTION = "com.android.deskclock.ALARM_DONE";
    public static final String ALARM_SNOOZE_ACTION = "com.android.deskclock.ALARM_SNOOZE";

    public static final String SERVICECMD = "com.android.music.musicservicecommand";
    public static final String CMDNAME = "command";
    public static final String CMDTOGGLEPAUSE = "togglepause";
    public static final String CMDSTOP = "stop";
    public static final String CMDPAUSE = "pause";
    public static final String CMDPREVIOUS = "previous";
    public static final String CMDNEXT = "next";
    public static final String CMDCYCLEREPEAT = "cyclerepeat";
    public static final String CMDTOGGLESHUFFLE = "toggleshuffle";

    public static final String TOGGLEPAUSE_ACTION = "com.android.music.musicservicecommand.togglepause";
    public static final String PAUSE_ACTION = "com.android.music.musicservicecommand.pause";
    public static final String PREVIOUS_ACTION = "com.android.music.musicservicecommand.previous";
    public static final String NEXT_ACTION = "com.android.music.musicservicecommand.next";
    public static final String CYCLEREPEAT_ACTION = "com.android.music.musicservicecommand.cyclerepeat";
    public static final String TOGGLESHUFFLE_ACTION = "com.android.music.musicservicecommand.toggleshuffle";
    private static final String PLAYSTATUS_REQUEST = "com.android.music.playstatusrequest";
    private static final String PLAYSTATUS_RESPONSE = "com.android.music.playstatusresponse";

    private static final int MAX_HISTORY_SIZE = 100;

    private static final float VOLUME_FULL = 1.0f;
    private static final float VOLUME_MUTE = 0.1f;

    private static final int FADE_UP_DURATION = 300; // ms
    private static final int FADE_DOWN_DURATION = 150; // ms
    private static final int FADE_STEP_DURATION = 10; // ms

    // TODO: make this logarithmic somehow.
    private static final float VOLUME_DOWN = (VOLUME_FULL - VOLUME_MUTE) * FADE_STEP_DURATION / FADE_DOWN_DURATION;
    private static final float VOLUME_UP = (VOLUME_FULL - VOLUME_MUTE) * FADE_STEP_DURATION / FADE_UP_DURATION;

    private MultiPlayer mPlayer;
    private String mFileToPlay;
    private int mShuffleMode = SHUFFLE_NONE;
    private int mRepeatMode = REPEAT_NONE;
    private int mMediaMountedCount = 0;
    private long [] mAutoShuffleList = null;
    private long [] mPlayList = null;
    private int mPlayListLen = 0;
    private Vector<Integer> mHistory = new Vector<Integer>(MAX_HISTORY_SIZE);
    private Cursor mCursor;
    private int mPlayPos = -1;
    private static final String LOGTAG = "MediaPlaybackService";
    private final Shuffler mRand = new Shuffler();
    private int mOpenFailedCounter = 0;
    String[] mCursorCols = new String[] {
            "audio._id AS _id",             // index must match IDCOLIDX below
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM_ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.ALBUM_ARTIST_ID,
            MediaStore.Audio.Media.IS_PODCAST, // index must match PODCASTCOLIDX below
            MediaStore.Audio.Media.BOOKMARK    // index must match BOOKMARKCOLIDX below
    };
    private final static int IDCOLIDX = 0;
    private final static int PODCASTCOLIDX = 10;
    private final static int BOOKMARKCOLIDX = 11;
    private BroadcastReceiver mUnmountReceiver = null;
    private BroadcastReceiver mA2dpReceiver = null;
    private WakeLock mWakeLock;
    private int mServiceStartId = -1;
    private boolean mServiceInUse = false;
    private boolean mIsSupposedToBePlaying = false;
    private boolean mQuietMode = false;
    private AudioManager mAudioManager;
    private boolean mQueueIsSaveable = true;
    // used to track what type of audio focus loss caused the playback to pause
    private boolean mPausedByTransientLossOfFocus = false;

	// Flip action
	public static int ROLL_LOVER = -25;
	public static int ROLL_UPER = 25;
	public static int PITCH_LOVER = -160;
	public static int PITCH_UPER = 160;
	// Sensitivity
	public static int FLIP_SENS = 0;
	public static double SHAKE_SENS = 0d;
	public static Shaker shaker;
	public static String shake_actions_db;
	private IMediaPlaybackService mService = null;
	// Flip
	private SensorManager sensorMan = null;
	private float PITCH;
	private float ROLL;
	private boolean IsWorked = false;

    private boolean mPausedByIncomingAlarm = false;
    // used to track current volume
    private float mCurrentVolume = 1.0f;

    private SharedPreferences mPreferences;
    // We use this to distinguish between different cards when saving/restoring playlists.
    // This will have to change if we want to support multiple simultaneous cards.
    private int mCardId;
    
    private MediaAppWidgetProvider4x1 mAppWidgetProvider4x1 = MediaAppWidgetProvider4x1.getInstance();
    private MediaAppWidgetProvider4x2 mAppWidgetProvider4x2 = MediaAppWidgetProvider4x2.getInstance();
    
    // interval after which we stop the service when idle
    private static final int IDLE_DELAY = 60000;

    private boolean mStartPlayback = false;

    private class MediaplayerHandler extends Handler {

        private static final int MESSAGE_TRACK_ENDED = 1;
        private static final int MESSAGE_RELEASE_WAKELOCK = 2;
        private static final int MESSAGE_SERVER_DIED = 3;
        private static final int MESSAGE_FOCUSCHANGE = 4;
        private static final int MESSAGE_FADE = 7;
        private static final int MESSAGE_STOP = 8;
        private static final int MESSAGE_PAUSE = 9;
        private static final int MESSAGE_NEXT = 10;
        private static final int MESSAGE_PREV = 11;
        // seek to position stored in targetpos
        private static final int MESSAGE_SEEK = 12;
        // seek to position stored in targetqueuepos
        private static final int MESSAGE_SET_QUEUEPOS = 13;

        // used to store target volume when fading
        private float mTargetVolume = mCurrentVolume;
        // used to store target pos when fade-seeking
        private long mTargetPos = 0;
        // used to store target queue pos when fade-seeking
        private int mTargetQueuePos = 0;

        @Override
        public void handleMessage(Message msg) {
            MusicUtils.debugLog("mMediaplayerHandler.handleMessage " + msg.what);
            Message andThen = null;
            if (msg.obj instanceof Message) {
                andThen = (Message) msg.obj;
            }
            boolean sendQueuedMessage = true;

            switch (msg.what) {
                case MESSAGE_PAUSE:
                    pause();
                    break;
                case MESSAGE_STOP:
                    stop();
                    break;
                case MESSAGE_NEXT:
                    next(true);
                    break;
                case MESSAGE_PREV:
                    prev();
                    break;
                case MESSAGE_SEEK:
                    seek(mTargetPos);
                    break;
                case MESSAGE_SET_QUEUEPOS:
                    setQueuePosition(mTargetQueuePos);
                    break;
                case MESSAGE_FADE:
                    final float targetVolume = mTargetVolume;
                    float currentVolume = mCurrentVolume;
                    float newVolume;
                    if (targetVolume > currentVolume) {
                        newVolume = currentVolume + VOLUME_UP;
                        if (newVolume > targetVolume)
                            newVolume = targetVolume;
                    }
                    else { // targetVolume <= currentVolume;
                        newVolume = currentVolume - VOLUME_DOWN;
                        if (newVolume < targetVolume)
                            newVolume = targetVolume;
                    }
                    mCurrentVolume = newVolume;
                    mPlayer.setVolume(newVolume);
                    if (newVolume != targetVolume) {
                        sendMessageDelayed(obtainMessage(MESSAGE_FADE, msg.obj), 10);
                        sendQueuedMessage = false;
                    }
                    break;
                case MESSAGE_SERVER_DIED:
                    if (mIsSupposedToBePlaying) {
                        next(true);
                    } else {
                        // the server died when we were idle, so just
                        // reopen the same song (it will start again
                        // from the beginning though when the user
                        // restarts)
                        openCurrent();
                    }
                    break;
                case MESSAGE_TRACK_ENDED:
                    if (mRepeatMode == REPEAT_CURRENT) {
                        seek(0);
                        play();
                    } else {
                        next(false);
                    }
                    break;
                case MESSAGE_RELEASE_WAKELOCK:
                    mWakeLock.release();
                    break;
                case MESSAGE_FOCUSCHANGE:
                    // This code is here so we can better synchronize it with the code that
                    // handles fade-in
                    switch (msg.arg1) {
                        case AudioManager.AUDIOFOCUS_LOSS:
                            Log.v(LOGTAG, "AudioFocus: received AUDIOFOCUS_LOSS");
                            if(isPlaying()) {
                                mPausedByTransientLossOfFocus = false;
                            }
                            fadeDownAndPause();
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                            fadeDown();
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                            Log.v(LOGTAG, "AudioFocus: received AUDIOFOCUS_LOSS_TRANSIENT");
                            if (isPlaying()) {
                                SharedPreferences preferences = getSharedPreferences(MusicSettingsActivity.
                                        PREFERENCES_FILE, MODE_PRIVATE);
                                if (preferences.getBoolean(MusicSettingsActivity.KEY_ENABLE_FOCUS_LOSS_DUCKING,
                                        false)) {
                                    int duckAttenuationdB = Integer.valueOf(preferences.getString(
                                            MusicSettingsActivity.KEY_DUCK_ATTENUATION_DB,
                                            MusicSettingsActivity.DEFAULT_DUCK_ATTENUATION_DB));
                                    //Convert from decibels to volume level
                                    float duckVolume = (float) Math.pow(10.0, -duckAttenuationdB / 20.0);
                                    Log.v(LOGTAG, "New attentuated volume: " + duckVolume);
                                    mPlayer.setVolume(duckVolume);
                                } else {
                                    mPausedByTransientLossOfFocus = true;
                                    pause(); // don't move pause out because we have ducking
                                }
                            }
                            break;
                        case AudioManager.AUDIOFOCUS_GAIN:
                            Log.v(LOGTAG, "AudioFocus: received AUDIOFOCUS_GAIN");
                            if(isPlaying() || mPausedByTransientLossOfFocus) {
                                TelephonyManager telephonyManager =
                                    (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);

                                while (telephonyManager.getCallState() == TelephonyManager.CALL_STATE_OFFHOOK) {
                                    try{
                                        Thread.sleep(500);
                                    }
                                    catch (InterruptedException e)  {
                                        e.printStackTrace();
                                    }
                                }
                                mPausedByTransientLossOfFocus = false;
                                play(); // also queues a fade-in
                            } else {
                                fadeUp();
                            }
                            break;
                        default:
                            Log.e(LOGTAG, "Unknown audio focus change code");
                    }
                    break;

                default:
                    break;
            }

            if (sendQueuedMessage && andThen != null) {
                sendMessage(andThen);
            }
        }
    }

    private MediaplayerHandler mMediaplayerHandler = new MediaplayerHandler();

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            String cmd = intent.getStringExtra("command");
            MusicUtils.debugLog("mIntentReceiver.onReceive " + action + " / " + cmd);
            if (CMDNEXT.equals(cmd) || NEXT_ACTION.equals(action)) {
                fadeDownAndNext();
            } else if (CMDPREVIOUS.equals(cmd) || PREVIOUS_ACTION.equals(action)) {
                fadeDownAndPrev();
            } else if (CMDTOGGLEPAUSE.equals(cmd) || TOGGLEPAUSE_ACTION.equals(action)) {
                if (isPlaying()) {
                    fadeDownAndPause();
                    mPausedByTransientLossOfFocus = false;
                } else {
                    play();
                }
            } else if (CMDPAUSE.equals(cmd) || PAUSE_ACTION.equals(action)) {
                fadeDownAndPause();
                mPausedByTransientLossOfFocus = false;
            } else if (CMDSTOP.equals(cmd)) {
                fadeDownAndStop();
                mPausedByTransientLossOfFocus = false;
                // seek(0);
            } else if (CMDCYCLEREPEAT.equals(cmd) || CYCLEREPEAT_ACTION.equals(action)) {
                cycleRepeat();
            } else if (CMDTOGGLESHUFFLE.equals(cmd) || TOGGLESHUFFLE_ACTION.equals(action)) {
                toggleShuffle();
            } else if (MediaAppWidgetProvider4x1.CMDAPPWIDGETUPDATE.equals(cmd)) {
                // Someone asked us to refresh a set of specific widgets, probably
                // because they were just added.
                int[] appWidgetIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);
                mAppWidgetProvider4x1.performUpdate(MediaPlaybackService.this, appWidgetIds);
            } else if (MediaAppWidgetProvider4x2.CMDAPPWIDGETUPDATE.equals(cmd)) {
                // Someone asked us to refresh a set of specific widgets, probably
                // because they were just added.
                int[] appWidgetIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);
                mAppWidgetProvider4x2.performUpdate(MediaPlaybackService.this, appWidgetIds);
            } else if (ALARM_ALERT_ACTION.equals(action)) {
                if (isPlaying()) {
                    mPausedByIncomingAlarm = true;
                    pause();
                }
            } else if (ALARM_DONE_ACTION.equals(action)) {
                if (mPausedByIncomingAlarm) {
                    play();
                }
            }
        }
    };

    private OnAudioFocusChangeListener mAudioFocusListener = new OnAudioFocusChangeListener() {
        public void onAudioFocusChange(int focusChange) {
            mMediaplayerHandler.obtainMessage(MediaplayerHandler.MESSAGE_FOCUSCHANGE, focusChange, 0).sendToTarget();
        }
    };

    private PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        public void onCallStateChanged(int state, String incomingNumber) {
            switch (state) {
                case TelephonyManager.CALL_STATE_RINGING:
                    Log.v(LOGTAG, "PhoneState: received CALL_STATE_RINGING");
                    if (isPlaying()) {
                        mPausedByTransientLossOfFocus = true;
                        pause();
                    }
                    break;

                case TelephonyManager.CALL_STATE_OFFHOOK:
                    Log.v(LOGTAG, "PhoneState: received CALL_STATE_OFFHOOK");
                    break;
            }
        }
    };

    public MediaPlaybackService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();

		SharedPreferences mPrefs = PreferenceManager
				.getDefaultSharedPreferences(this);

		Double shakeChange = new Double(mPrefs.getInt(
				MusicSettingsActivity.SHAKE_SENSITIVITY,
				(int) (MusicSettingsActivity.DEFAULT_SHAKE_SENS)));

		SHAKE_SENS = shakeChange;

		if (SHAKE_SENS == 0) {
			new Shaker(this, 1.25, 500, this);
		} else {
			new Shaker(this, SHAKE_SENS + .25d, 500, this);
		}

		// Flip action
		sensorMan = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
		sensorMan.registerListener(this,
				sensorMan.getDefaultSensor(Sensor.TYPE_ORIENTATION),
				SensorManager.SENSOR_DELAY_UI);

        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mAudioManager.registerMediaButtonEventReceiver(new ComponentName(getPackageName(),
                MediaButtonIntentReceiver.class.getName()));

        mPreferences = getSharedPreferences("Music", MODE_WORLD_READABLE | MODE_WORLD_WRITEABLE);
        mCardId = MusicUtils.getCardId(this);

        registerExternalStorageListener();
        registerA2dpServiceListener();

        // Needs to be done in this thread, since otherwise ApplicationContext.getPowerManager() crashes.
        mPlayer = new MultiPlayer();
        mPlayer.setHandler(mMediaplayerHandler);

        reloadQueue();

        IntentFilter commandFilter = new IntentFilter();
        commandFilter.addAction(SERVICECMD);
        commandFilter.addAction(TOGGLEPAUSE_ACTION);
        commandFilter.addAction(PAUSE_ACTION);
        commandFilter.addAction(NEXT_ACTION);
        commandFilter.addAction(PREVIOUS_ACTION);
        commandFilter.addAction(CYCLEREPEAT_ACTION);
        commandFilter.addAction(TOGGLESHUFFLE_ACTION);
        commandFilter.addAction(PLAYSTATUS_REQUEST);
        commandFilter.addAction(ALARM_ALERT_ACTION);
        commandFilter.addAction(ALARM_DONE_ACTION);
        commandFilter.addAction(ALARM_SNOOZE_ACTION);
        registerReceiver(mIntentReceiver, commandFilter);

        PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, this.getClass().getName());
        mWakeLock.setReferenceCounted(false);

        // If the service was idle, but got killed before it stopped itself, the
        // system will relaunch it. Make sure it gets stopped again in that case.
        Message msg = mDelayedStopHandler.obtainMessage();
        mDelayedStopHandler.sendMessageDelayed(msg, IDLE_DELAY);
    }

    @Override
    public void onDestroy() {
		sensorMan.unregisterListener(this);
        // Check that we're not being destroyed while something is still playing.
        if (isPlaying()) {
            Log.e(LOGTAG, "Service being destroyed while still playing.");
        }
        // release all MediaPlayer resources, including the native player and wakelocks
        Intent i = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, getAudioSessionId());
        i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
        sendBroadcast(i);
        mPlayer.release();
        mPlayer = null;

        mAudioManager.abandonAudioFocus(mAudioFocusListener);

        TelephonyManager telephonyManager =
            (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        telephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);

        // make sure there aren't any other messages coming
        mDelayedStopHandler.removeCallbacksAndMessages(null);
        mMediaplayerHandler.removeCallbacksAndMessages(null);

        if (mCursor != null) {
            mCursor.close();
            mCursor = null;
        }

        unregisterReceiver(mIntentReceiver);
        unregisterReceiver(mA2dpReceiver);
        if (mUnmountReceiver != null) {
            unregisterReceiver(mUnmountReceiver);
            mUnmountReceiver = null;
        }
        mWakeLock.release();
        super.onDestroy();
    }
    
    private final char hexdigits [] = new char [] {
            '0', '1', '2', '3',
            '4', '5', '6', '7',
            '8', '9', 'a', 'b',
            'c', 'd', 'e', 'f'
    };

    private void saveQueue(boolean full) {
        if (!mQueueIsSaveable) {
            return;
        }

        Editor ed = mPreferences.edit();
        //long start = System.currentTimeMillis();
        if (full) {
            StringBuilder q = new StringBuilder();
            
            // The current playlist is saved as a list of "reverse hexadecimal"
            // numbers, which we can generate faster than normal decimal or
            // hexadecimal numbers, which in turn allows us to save the playlist
            // more often without worrying too much about performance.
            // (saving the full state takes about 40 ms under no-load conditions
            // on the phone)
            int len = mPlayListLen;
            for (int i = 0; i < len; i++) {
                long n = mPlayList[i];
                if (n < 0) {
                    continue;
                } else if (n == 0) {
                    q.append("0;");
                } else {
                    while (n != 0) {
                        int digit = (int)(n & 0xf);
                        n >>>= 4;
                        q.append(hexdigits[digit]);
                    }
                    q.append(";");
                }
            }
            //Log.i("@@@@ service", "created queue string in " + (System.currentTimeMillis() - start) + " ms");
            ed.putString("queue", q.toString());
            ed.putInt("cardid", mCardId);
            if (mShuffleMode != SHUFFLE_NONE) {
                // In shuffle mode we need to save the history too
                len = mHistory.size();
                q.setLength(0);
                for (int i = 0; i < len; i++) {
                    int n = mHistory.get(i);
                    if (n == 0) {
                        q.append("0;");
                    } else {
                        while (n != 0) {
                            int digit = (n & 0xf);
                            n >>>= 4;
                            q.append(hexdigits[digit]);
                        }
                        q.append(";");
                    }
                }
                ed.putString("history", q.toString());
            }
        }
        ed.putInt("curpos", mPlayPos);
        if (mPlayer.isInitialized()) {
            ed.putLong("seekpos", mPlayer.position());
        }
        ed.putInt("repeatmode", mRepeatMode);
        ed.putInt("shufflemode", mShuffleMode);
        SharedPreferencesCompat.apply(ed);

        //Log.i("@@@@ service", "saved state in " + (System.currentTimeMillis() - start) + " ms");
    }

    private void reloadQueue() {
        String q = null;

        int id = mCardId;
        if (mPreferences.contains("cardid")) {
            id = mPreferences.getInt("cardid", ~mCardId);
        }
        if (id == mCardId) {
            // Only restore the saved playlist if the card is still
            // the same one as when the playlist was saved
            q = mPreferences.getString("queue", "");
        }
        int qlen = q != null ? q.length() : 0;
        if (qlen > 1) {
            //Log.i("@@@@ service", "loaded queue: " + q);
            int plen = 0;
            int n = 0;
            int shift = 0;
            for (int i = 0; i < qlen; i++) {
                char c = q.charAt(i);
                if (c == ';') {
                    ensurePlayListCapacity(plen + 1);
                    mPlayList[plen] = n;
                    plen++;
                    n = 0;
                    shift = 0;
                } else {
                    if (c >= '0' && c <= '9') {
                        n += ((c - '0') << shift);
                    } else if (c >= 'a' && c <= 'f') {
                        n += ((10 + c - 'a') << shift);
                    } else {
                        // bogus playlist data
                        plen = 0;
                        break;
                    }
                    shift += 4;
                }
            }
            mPlayListLen = plen;

            int pos = mPreferences.getInt("curpos", 0);
            if (pos < 0 || pos >= mPlayListLen) {
                // The saved playlist is bogus, discard it
                mPlayListLen = 0;
                return;
            }
            mPlayPos = pos;

            // When reloadQueue is called in response to a card-insertion,
            // we might not be able to query the media provider right away.
            // To deal with this, try querying for the current file, and if
            // that fails, wait a while and try again. If that too fails,
            // assume there is a problem and don't restore the state.
            Cursor crsr = MusicUtils.query(this,
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        new String [] {"_id"}, "_id=" + mPlayList[mPlayPos] , null, null);
            if (crsr == null || crsr.getCount() == 0) {
                // wait a bit and try again
                SystemClock.sleep(3000);
                crsr = getContentResolver().query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        mCursorCols, "_id=" + mPlayList[mPlayPos] , null, null);
            }
            if (crsr != null) {
                crsr.close();
            }

            // Make sure we don't auto-skip to the next song, since that
            // also starts playback. What could happen in that case is:
            // - music is paused
            // - go to UMS and delete some files, including the currently playing one
            // - come back from UMS
            // (time passes)
            // - music app is killed for some reason (out of memory)
            // - music service is restarted, service restores state, doesn't find
            //   the "current" file, goes to the next and: playback starts on its
            //   own, potentially at some random inconvenient time.
            mOpenFailedCounter = 20;
            mQuietMode = true;
            openCurrent();
            mQuietMode = false;
            if (!mPlayer.isInitialized()) {
                // couldn't restore the saved state
                mPlayListLen = 0;
                return;
            }

            long seekpos = mPreferences.getLong("seekpos", 0);
            seek(seekpos >= 0 && seekpos < duration() ? seekpos : 0);
            Log.d(LOGTAG, "restored queue, currently at position "
                    + position() + "/" + duration()
                    + " (requested " + seekpos + ")");

            int repmode = mPreferences.getInt("repeatmode", REPEAT_NONE);
            if (repmode != REPEAT_ALL && repmode != REPEAT_CURRENT) {
                repmode = REPEAT_NONE;
            }
            mRepeatMode = repmode;

            int shufmode = mPreferences.getInt("shufflemode", SHUFFLE_NONE);
            if (shufmode != SHUFFLE_AUTO && shufmode != SHUFFLE_NORMAL) {
                shufmode = SHUFFLE_NONE;
            }
            if (shufmode != SHUFFLE_NONE) {
                // in shuffle mode we need to restore the history too
                q = mPreferences.getString("history", "");
                qlen = q != null ? q.length() : 0;
                if (qlen > 1) {
                    plen = 0;
                    n = 0;
                    shift = 0;
                    mHistory.clear();
                    for (int i = 0; i < qlen; i++) {
                        char c = q.charAt(i);
                        if (c == ';') {
                            if (n >= mPlayListLen) {
                                // bogus history data
                                mHistory.clear();
                                break;
                            }
                            mHistory.add(n);
                            n = 0;
                            shift = 0;
                        } else {
                            if (c >= '0' && c <= '9') {
                                n += ((c - '0') << shift);
                            } else if (c >= 'a' && c <= 'f') {
                                n += ((10 + c - 'a') << shift);
                            } else {
                                // bogus history data
                                mHistory.clear();
                                break;
                            }
                            shift += 4;
                        }
                    }
                }
            }
            if (shufmode == SHUFFLE_AUTO) {
                if (! makeAutoShuffleList()) {
                    shufmode = SHUFFLE_NONE;
                }
            }
            mShuffleMode = shufmode;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        mDelayedStopHandler.removeCallbacksAndMessages(null);
        mServiceInUse = true;
        return mBinder;
    }

    @Override
    public void onRebind(Intent intent) {
        mDelayedStopHandler.removeCallbacksAndMessages(null);
        mServiceInUse = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mServiceStartId = startId;
        mDelayedStopHandler.removeCallbacksAndMessages(null);

        if (intent != null) {
            String action = intent.getAction();
            String cmd = intent.getStringExtra("command");
            MusicUtils.debugLog("onStartCommand " + action + " / " + cmd);

            if (CMDNEXT.equals(cmd) || NEXT_ACTION.equals(action)) {
                fadeDownAndNext();
            } else if (CMDPREVIOUS.equals(cmd) || PREVIOUS_ACTION.equals(action)) {
                if (position() < 2000) {
                    fadeDownAndPrev();
                } else {
                    fadeDownAndSeek(0);
                }
            } else if (CMDTOGGLEPAUSE.equals(cmd) || TOGGLEPAUSE_ACTION.equals(action)) {
                if (isPlaying()) {
                    fadeDownAndPause();
                    mPausedByTransientLossOfFocus = false;
                } else {
                    play();
                }
            } else if (CMDPAUSE.equals(cmd) || PAUSE_ACTION.equals(action)) {
                fadeDownAndPause();
                mPausedByTransientLossOfFocus = false;
            } else if (CMDSTOP.equals(cmd)) {
                fadeDownAndStop();
                mPausedByTransientLossOfFocus = false;
            } else if (CMDCYCLEREPEAT.equals(cmd) || CYCLEREPEAT_ACTION.equals(action)) {
                cycleRepeat();
            } else if (CMDTOGGLESHUFFLE.equals(cmd) || TOGGLESHUFFLE_ACTION.equals(action)) {
                toggleShuffle();
            } else if (PLAYSTATUS_REQUEST.equals(action)) {
                notifyChange(PLAYSTATUS_RESPONSE);
            }
        }

        // make sure the service will shut down on its own if it was
        // just started but not bound to and nothing is playing
        mDelayedStopHandler.removeCallbacksAndMessages(null);
        Message msg = mDelayedStopHandler.obtainMessage();
        mDelayedStopHandler.sendMessageDelayed(msg, IDLE_DELAY);
        return START_STICKY;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        mServiceInUse = false;

        // Take a snapshot of the current playlist
        saveQueue(true);

        if (isPlaying() || mPausedByTransientLossOfFocus) {
            // something is currently playing, or will be playing once 
            // an in-progress action requesting audio focus ends, so don't stop the service now.
            return true;
        }

        // If there is a playlist but playback is paused, then wait a while
        // before stopping the service, so that pause/resume isn't slow.
        // Also delay stopping the service if we're transitioning between tracks.
        if (mPlayListLen > 0 || mMediaplayerHandler.hasMessages(MediaplayerHandler.MESSAGE_TRACK_ENDED)) {
            Message msg = mDelayedStopHandler.obtainMessage();
            mDelayedStopHandler.sendMessageDelayed(msg, IDLE_DELAY);
            return true;
        }

        // No active playlist, OK to stop the service right now
        stopSelf(mServiceStartId);
        return true;
    }

    private Handler mDelayedStopHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            // Check again to make sure nothing is playing right now
            if (isPlaying() || mPausedByTransientLossOfFocus || mServiceInUse
                    || mMediaplayerHandler.hasMessages(MediaplayerHandler.MESSAGE_TRACK_ENDED)) {
                return;
            }
            // save the queue again, because it might have changed
            // since the user exited the music app (because of
            // party-shuffle or because the play-position changed)
            saveQueue(true);
            stopSelf(mServiceStartId);
        }
    };

    /**
     * Called when we receive a ACTION_MEDIA_EJECT notification.
     *
     * @param storagePath path to mount point for the removed media
     */
    public void closeExternalStorageFiles(String storagePath) {
        // stop playback and clean up if the SD card is going to be unmounted.
        stop(true);
        notifyChange(QUEUE_CHANGED);
        notifyChange(META_CHANGED);
    }

    /**
     * Registers an intent to listen for ACTION_MEDIA_EJECT notifications.
     * The intent will call closeExternalStorageFiles() if the external media
     * is going to be ejected, so applications can clean up any files they have open.
     */
    public void registerExternalStorageListener() {
        if (mUnmountReceiver == null) {
            mUnmountReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (action.equals(Intent.ACTION_MEDIA_EJECT)) {
                        saveQueue(true);
                        mQueueIsSaveable = false;
                        closeExternalStorageFiles(intent.getData().getPath());
                    } else if (action.equals(Intent.ACTION_MEDIA_MOUNTED)) {
                        mMediaMountedCount++;
                        mCardId = MusicUtils.getCardId(MediaPlaybackService.this);
                        reloadQueue();
                        mQueueIsSaveable = true;
                        notifyChange(QUEUE_CHANGED);
                        notifyChange(META_CHANGED);
                    }
                }
            };
            IntentFilter iFilter = new IntentFilter();
            iFilter.addAction(Intent.ACTION_MEDIA_EJECT);
            iFilter.addAction(Intent.ACTION_MEDIA_MOUNTED);
            iFilter.addDataScheme("file");
            registerReceiver(mUnmountReceiver, iFilter);
        }
    }

    public void registerA2dpServiceListener() {
        mA2dpReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action.equals(PLAYSTATUS_REQUEST)) {
                    notifyChange(PLAYSTATUS_RESPONSE);
                }
            }
        };
        IntentFilter iFilter = new IntentFilter();
        iFilter.addAction(PLAYSTATUS_REQUEST);
        registerReceiver(mA2dpReceiver, iFilter);
    }

    /**
     * Notify the change-receivers that something has changed.
     * The intent that is sent contains the following data
     * for the currently playing track:
     * "id" - Integer: the database row ID
     * "artist" - String: the name of the artist
     * "album_artist" - String: the name of the album artist
     * "album" - String: the name of the album
     * "track" - String: the name of the track
     * The intent has an action that is one of
     * "com.android.music.metachanged"
     * "com.android.music.queuechanged",
     * "com.android.music.playbackcomplete"
     * "com.android.music.playstatechanged"
     * respectively indicating that a new track has
     * started playing, that the playback queue has
     * changed, that playback has stopped because
     * the last file in the list has been played,
     * or that the play-state changed (paused/resumed).
     */
    private void notifyChange(String what) {
        
        Intent i = new Intent(what);
        i.putExtra("id", Long.valueOf(getAudioId()));
        i.putExtra("artist", getArtistName());
        i.putExtra("album_artist", getAlbumartistName());
        i.putExtra("album",getAlbumName());
        i.putExtra("track", getTrackName());
        i.putExtra("playing", isPlaying());
        i.putExtra("songid", getAudioId());
        i.putExtra("albumid", getAlbumId());
        i.putExtra("duration", duration());
        i.putExtra("position", position());
        if (mPlayList != null)
            i.putExtra("ListSize", Long.valueOf(mPlayList.length));
        else
            i.putExtra("ListSize", Long.valueOf(mPlayListLen));
        sendStickyBroadcast(i);

        if (what.equals(QUEUE_CHANGED)) {
            saveQueue(true);
        } else {
            saveQueue(false);
        }

        // Share this notification directly with our widgets
        mAppWidgetProvider4x1.notifyChange(this, what);
        mAppWidgetProvider4x2.notifyChange(this, what);
    }

    private void ensurePlayListCapacity(int size) {
        if (mPlayList == null || size > mPlayList.length) {
            // reallocate at 2x requested size so we don't
            // need to grow and copy the array for every
            // insert
            long [] newlist = new long[size * 2];
            int len = mPlayList != null ? mPlayList.length : mPlayListLen;
            for (int i = 0; i < len; i++) {
                newlist[i] = mPlayList[i];
            }
            mPlayList = newlist;
        }
        // FIXME: shrink the array when the needed size is much smaller
        // than the allocated size
    }

    // insert the list of songs at the specified position in the playlist
    private void addToPlayList(long [] list, int position) {
        int addlen = list.length;
        if (position < 0) { // overwrite
            mPlayListLen = 0;
            position = 0;
        }
        ensurePlayListCapacity(mPlayListLen + addlen);
        if (position > mPlayListLen) {
            position = mPlayListLen;
        }
        
        // move part of list after insertion point
        int tailsize = mPlayListLen - position;
        for (int i = tailsize ; i > 0 ; i--) {
            mPlayList[position + i] = mPlayList[position + i - addlen]; 
        }
        
        // copy list into playlist
        for (int i = 0; i < addlen; i++) {
            mPlayList[position + i] = list[i];
        }
        mPlayListLen += addlen;
        if (mPlayListLen == 0) {
            mCursor.close();
            mCursor = null;
            notifyChange(META_CHANGED);
        }
    }

    /**
     * Appends a list of tracks to the current playlist.
     * If nothing is playing currently, playback will be started at
     * the first track.
     * If the action is NOW, playback will switch to the first of
     * the new tracks immediately.
     * @param list The list of tracks to append.
     * @param action NOW, NEXT or LAST
     */
    public void enqueue(long [] list, int action) {
        synchronized(this) {
            if (action == NEXT && mPlayPos + 1 < mPlayListLen) {
                addToPlayList(list, mPlayPos + 1);
                notifyChange(QUEUE_CHANGED);
            } else {
                // action == LAST || action == NOW || mPlayPos + 1 == mPlayListLen
                addToPlayList(list, Integer.MAX_VALUE);
                notifyChange(QUEUE_CHANGED);
                if (action == NOW) {
                    mPlayPos = mPlayListLen - list.length;
                    openCurrent();
                    play();
                    notifyChange(META_CHANGED);
                    return;
                }
            }
            if (mPlayPos < 0) {
                mPlayPos = 0;
                openCurrent();
                play();
                notifyChange(META_CHANGED);
            }
        }
    }

    /**
     * Replaces the current playlist with a new list,
     * and prepares for starting playback at the specified
     * position in the list, or a random position if the
     * specified position is 0.
     * @param list The new list of tracks.
     */
    public void open(long [] list, int position) {
        synchronized (this) {
            if (mShuffleMode == SHUFFLE_AUTO) {
                mShuffleMode = SHUFFLE_NORMAL;
            }
            long oldId = getAudioId();
            int listlength = list.length;
            boolean newlist = true;
            if (mPlayListLen == listlength) {
                // possible fast path: list might be the same
                newlist = false;
                for (int i = 0; i < listlength; i++) {
                    if (list[i] != mPlayList[i]) {
                        newlist = true;
                        break;
                    }
                }
            }
            if (newlist) {
                addToPlayList(list, -1);
                notifyChange(QUEUE_CHANGED);
            }
            if (position >= 0) {
                mPlayPos = position;
            } else {
                mPlayPos = mRand.nextInt(mPlayListLen);
            }
            mHistory.clear();

            saveBookmarkIfNeeded();
            openCurrent();
            if (oldId != getAudioId()) {
                notifyChange(META_CHANGED);
            }
        }
    }

    /**
     * Moves the item at index1 to index2.
     * @param index1
     * @param index2
     */
    public void moveQueueItem(int index1, int index2) {
        synchronized (this) {
            if (index1 >= mPlayListLen) {
                index1 = mPlayListLen - 1;
            }
            if (index2 >= mPlayListLen) {
                index2 = mPlayListLen - 1;
            }
            if (index1 < index2) {
                long tmp = mPlayList[index1];
                for (int i = index1; i < index2; i++) {
                    mPlayList[i] = mPlayList[i+1];
                }
                mPlayList[index2] = tmp;
                if (mPlayPos == index1) {
                    mPlayPos = index2;
                } else if (mPlayPos >= index1 && mPlayPos <= index2) {
                        mPlayPos--;
                }
            } else if (index2 < index1) {
                long tmp = mPlayList[index1];
                for (int i = index1; i > index2; i--) {
                    mPlayList[i] = mPlayList[i-1];
                }
                mPlayList[index2] = tmp;
                if (mPlayPos == index1) {
                    mPlayPos = index2;
                } else if (mPlayPos >= index2 && mPlayPos <= index1) {
                        mPlayPos++;
                }
            }
            notifyChange(QUEUE_CHANGED);
        }
    }

    /**
     * Returns the current play list
     * @return An array of integers containing the IDs of the tracks in the play list
     */
    public long [] getQueue() {
        synchronized (this) {
            int len = mPlayListLen;
            long [] list = new long[len];
            for (int i = 0; i < len; i++) {
                list[i] = mPlayList[i];
            }
            return list;
        }
    }

    private void openCurrent() {
        synchronized (this) {
            if (mCursor != null) {
                mCursor.close();
                mCursor = null;
            }

            if (mPlayListLen == 0) {
                return;
            }
            stop(false);

            String id = String.valueOf(mPlayList[mPlayPos]);

            mCursor = getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    mCursorCols, "_id=" + id , null, null);
            if (mCursor != null) {
                mCursor.moveToFirst();
                open(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI + "/" + id);
                // go to bookmark if needed
                if (isPodcast()) {
                    long bookmark = getBookmark();
                    // Start playing a little bit before the bookmark,
                    // so it's easier to get back in to the narrative.
                    seek(bookmark - 5000);
                }
            }
        }
    }

    /**
     * Opens the specified file and readies it for playback.
     *
     * @param path The full path of the file to be opened.
     */
    public void open(String path) {
        synchronized (this) {
            if (path == null) {
                return;
            }

            // if mCursor is null, try to associate path with a database cursor
            if (mCursor == null) {

                ContentResolver resolver = getContentResolver();
                Uri uri;
                String where;
                String selectionArgs[];
                if (path.startsWith("content://media/")) {
                    uri = Uri.parse(path);
                    where = null;
                    selectionArgs = null;
                } else {
                   uri = MediaStore.Audio.Media.getContentUriForPath(path);
                   where = MediaStore.Audio.Media.DATA + "=?";
                   selectionArgs = new String[] { path };
                }

                try {
                    mCursor = resolver.query(uri, mCursorCols, where, selectionArgs, null);
                    if  (mCursor != null) {
                        if (mCursor.getCount() == 0) {
                            mCursor.close();
                            mCursor = null;
                        } else {
                            mCursor.moveToNext();
                            ensurePlayListCapacity(1);
                            mPlayListLen = 1;
                            mPlayList[0] = mCursor.getLong(IDCOLIDX);
                            mPlayPos = 0;
                        }
                    }
                } catch (UnsupportedOperationException ex) {
                }
            }
            mFileToPlay = path;
            mPlayer.setDataSource(mFileToPlay);
            if (! mPlayer.isInitialized()) {
                stop(true);
                if (mOpenFailedCounter++ < 10 &&  mPlayListLen > 1) {
                    // beware: this ends up being recursive because next() calls open() again.
                    next(false);
                }
                if (! mPlayer.isInitialized() && mOpenFailedCounter != 0) {
                    // need to make sure we only shows this once
                    mOpenFailedCounter = 0;
                    if (!mQuietMode) {
                        Toast.makeText(this, R.string.playback_failed, Toast.LENGTH_SHORT).show();
                    }
                    Log.d(LOGTAG, "Failed to open file for playback");
                }
            } else {
                mOpenFailedCounter = 0;
            }
        }
    }

    /**
     * Starts playback of a previously opened file.
     */
    public void play() {
        TelephonyManager telephonyManager =
            (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        if (telephonyManager.getCallState() == TelephonyManager.CALL_STATE_OFFHOOK) {
            return;
        }

        mAudioManager.requestAudioFocus(mAudioFocusListener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN);
        mAudioManager.registerMediaButtonEventReceiver(new ComponentName(this.getPackageName(),
                MediaButtonIntentReceiver.class.getName()));

        telephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);

        if (mPlayer.isInitialized()) {
            // if we are at the end of the song, go to the next song first
            long duration = mPlayer.duration();
            if (mRepeatMode != REPEAT_CURRENT && duration > 2000 &&
                mPlayer.position() >= duration - 2000) {
                next(true);
            }

            mPlayer.start();
            // make sure we fade in, in case a previous fadein was stopped because
            // of another focus loss
            fadeUp();
           if (Settings.System.getInt(getContentResolver(), Settings.System.EXPANDED_VIEW_WIDGET, 0) == 0) {
             RemoteViews views = new RemoteViews(getPackageName(),
					R.layout.statusbar);
			views.setImageViewBitmap(R.id.icon, MusicUtils.getArtwork(
					getBaseContext(), getAudioId(), getAlbumId(), true));
			SharedPreferences preferences = getSharedPreferences(
					MusicSettingsActivity.PREFERENCES_FILE, MODE_PRIVATE);
			mPreferences.getBoolean(MusicSettingsActivity.KEY_TICK, false);
			if (preferences.getBoolean(
					MusicSettingsActivity.KEY_ENABLE_STATUS_SONG_TEXT, true)) {
				views.setViewVisibility(R.id.trackname, View.VISIBLE);
			} else {
				views.setViewVisibility(R.id.trackname, View.INVISIBLE);

			}
			if (preferences.getBoolean(
					MusicSettingsActivity.KEY_ENABLE_STATUS_ARTIST_TEXT, true)) {
				views.setViewVisibility(R.id.artist, View.VISIBLE);
			} else {
				views.setViewVisibility(R.id.artist, View.GONE);

			}
			if (preferences.getBoolean(
					MusicSettingsActivity.KEY_ENABLE_STATUS_ALBUM_ART, true)) {
				views.setViewVisibility(R.id.icon, View.VISIBLE);
				views.setViewVisibility(R.id.status_icon, View.GONE);

			} else {
				views.setViewVisibility(R.id.icon, View.GONE);
				views.setViewVisibility(R.id.status_icon, View.VISIBLE);
				views.setImageViewResource(R.id.status_icon,
						R.drawable.stat_notify_musicplayer);

			}
			if (preferences.getBoolean(
					MusicSettingsActivity.KEY_ENABLE_STATUS_NONYA, false)) {
				views.setViewVisibility(R.id.icon, View.GONE);
				views.setViewVisibility(R.id.status_icon, View.GONE);

			}

			if (getAudioId() < 0) {
				// streaming
				views.setTextViewText(R.id.trackname, getPath());
				views.setTextViewText(R.id.artist, null);
			} else {
				String artist = getArtistName();
				views.setTextViewText(R.id.trackname, getTrackName());
				if (artist == null || artist.equals(MediaStore.UNKNOWN_STRING)) {
					artist = getString(R.string.unknown_artist_name);
				}
				String album = getAlbumName();
				if (album == null || album.equals(MediaStore.UNKNOWN_STRING)) {
					album = getString(R.string.unknown_album_name);
				}
				views.setTextViewText(R.id.artist, getString(R.string.notification_artist_album, artist, album));
			}

             Notification status = new Notification();
             status.contentView = views;
             status.flags |= Notification.FLAG_ONGOING_EVENT;
             status.icon = R.drawable.stat_notify_musicplayer;
			if (preferences.getBoolean(MusicSettingsActivity.KEY_TICK, true)) {
				status.tickerText = getTrackName() + " by " + getArtistName();
			}
             status.contentIntent = PendingIntent.getActivity(this, 0,
                    new Intent("com.android.music.PLAYBACK_VIEWER")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 0);
             startForeground(PLAYBACKSERVICE_STATUS, status);
            }
            if (!mIsSupposedToBePlaying) {
                mIsSupposedToBePlaying = true;
                notifyChange(PLAYSTATE_CHANGED);
            }
            mPausedByIncomingAlarm = false;
        } else if (mPlayListLen <= 0) {
            // This is mostly so that if you press 'play' on a bluetooth headset
            // without every having played anything before, it will still play
            // something.
            setShuffleMode(SHUFFLE_AUTO);
        }
    }

    private void stop(boolean remove_status_icon) {
        if (mPlayer.isInitialized()) {
            mPlayer.stop();
        }
        mFileToPlay = null;
        if (mCursor != null) {
            mCursor.close();
            mCursor = null;
        }
        if (remove_status_icon) {
            gotoIdleState();
        } else {
            stopForeground(false);
        }
        if (remove_status_icon) {
            mIsSupposedToBePlaying = false;
        }
    }

    /**
     * Stops playback.
     */
    public void stop() {
        stop(true);
    }

    /**
     * Pauses playback (call play() to resume)
     */
    public void pause() {
        synchronized(this) {
            fadeDown();
            if (isPlaying()) {
                mPlayer.pause();
                gotoIdleState();
                mIsSupposedToBePlaying = false;
                notifyChange(PLAYSTATE_CHANGED);
                saveBookmarkIfNeeded();
            }
        }
    }

    private void fade(float newVolume, Message andThen) {
        mMediaplayerHandler.removeMessages(MediaplayerHandler.MESSAGE_FADE);
        mMediaplayerHandler.mTargetVolume = newVolume;
        mMediaplayerHandler.obtainMessage(MediaplayerHandler.MESSAGE_FADE, andThen).sendToTarget();
    }

    private void fade(float newVolume) {
        fade(newVolume, null);
    }

    public void fadeUp() {
        fade(VOLUME_FULL);
    }

    public void fadeDown() {
        fade(VOLUME_MUTE);
    }

    public void fadeDownAndStop() {
        fade(VOLUME_MUTE, mMediaplayerHandler.obtainMessage(MediaplayerHandler.MESSAGE_STOP));
    }

    public void fadeDownAndPause() {
        fade(VOLUME_MUTE, mMediaplayerHandler.obtainMessage(MediaplayerHandler.MESSAGE_PAUSE));
    }

    public void fadeDownAndNext() {
        fade(VOLUME_MUTE, mMediaplayerHandler.obtainMessage(MediaplayerHandler.MESSAGE_NEXT));
    }

    public void fadeDownAndPrev() {
        fade(VOLUME_MUTE, mMediaplayerHandler.obtainMessage(MediaplayerHandler.MESSAGE_PREV));
    }

    public void fadeDownAndSeek(long pos) {
        mMediaplayerHandler.mTargetPos = pos;
        fade(VOLUME_MUTE, mMediaplayerHandler.obtainMessage(MediaplayerHandler.MESSAGE_SEEK));
    }

    public void fadeDownAndSetQueuePosition(int pos) {
        mMediaplayerHandler.mTargetQueuePos = pos;
        fade(VOLUME_MUTE, mMediaplayerHandler.obtainMessage(MediaplayerHandler.MESSAGE_SET_QUEUEPOS));
    }

    /** Returns whether something is currently playing
     *
     * @return true if something is playing (or will be playing shortly, in case
     * we're currently transitioning between tracks), false if not.
     */
    public boolean isPlaying() {
        return mIsSupposedToBePlaying;
    }

    /*
      Desired behavior for prev/next/shuffle:

      - NEXT will move to the next track in the list when not shuffling, and to
        a track randomly picked from the not-yet-played tracks when shuffling.
        If all tracks have already been played, pick from the full set, but
        avoid picking the previously played track if possible.
      - when shuffling, PREV will go to the previously played track. Hitting PREV
        again will go to the track played before that, etc. When the start of the
        history has been reached, PREV is a no-op.
        When not shuffling, PREV will go to the sequentially previous track (the
        difference with the shuffle-case is mainly that when not shuffling, the
        user can back up to tracks that are not in the history).

        Example:
        When playing an album with 10 tracks from the start, and enabling shuffle
        while playing track 5, the remaining tracks (6-10) will be shuffled, e.g.
        the final play order might be 1-2-3-4-5-8-10-6-9-7.
        When hitting 'prev' 8 times while playing track 7 in this example, the
        user will go to tracks 9-6-10-8-5-4-3-2. If the user then hits 'next',
        a random track will be picked again. If at any time user disables shuffling
        the next/previous track will be picked in sequential order again.
     */

    public void prev() {
        synchronized (this) {
            if (position() > 2000) {
                seek(0);
            } else {
                if (mShuffleMode == SHUFFLE_NORMAL) {
                    // go to previously-played track and remove it from the history
                    int histsize = mHistory.size();
                    if (histsize == 0) {
                        // prev is a no-op
                        fadeUp();
                        return;
                    }
                    Integer pos = mHistory.remove(histsize - 1);
                    mPlayPos = pos.intValue();
                } else {
                    if (mPlayPos > 0) {
                        mPlayPos--;
                    } else {
                        mPlayPos = mPlayListLen - 1;
                    }
                }
                saveBookmarkIfNeeded();
                stop(false);
                openCurrent();
            }
            play();
            notifyChange(META_CHANGED);
        }
    }

    public void next(boolean force) {
        synchronized (this) {
            if (mPlayListLen <= 0) {
                Log.d(LOGTAG, "No play queue");
                return;
            }

            if (mShuffleMode == SHUFFLE_NORMAL) {
                // Pick random next track from the not-yet-played ones
                // TODO: make it work right after adding/removing items in the queue.

                // Store the current file in the history, but keep the history at a
                // reasonable size
                if (mPlayPos >= 0) {
                    mHistory.add(mPlayPos);
                }
                if (mHistory.size() > MAX_HISTORY_SIZE) {
                    mHistory.removeElementAt(0);
                }

                int numTracks = mPlayListLen;
                int[] tracks = new int[numTracks];
                for (int i=0;i < numTracks; i++) {
                    tracks[i] = i;
                }

                int numHistory = mHistory.size();
                int numUnplayed = numTracks;
                for (int i=0;i < numHistory; i++) {
                    int idx = mHistory.get(i).intValue();
                    if (idx < numTracks && tracks[idx] >= 0) {
                        numUnplayed--;
                        tracks[idx] = -1;
                    }
                }

                // 'numUnplayed' now indicates how many tracks have not yet
                // been played, and 'tracks' contains the indices of those
                // tracks.
                if (numUnplayed <=0) {
                    // everything's already been played
                    if (mRepeatMode == REPEAT_ALL || force) {
                        //pick from full set
                        numUnplayed = numTracks;
                        for (int i=0;i < numTracks; i++) {
                            tracks[i] = i;
                        }
                    } else {
                        // all done
                        gotoIdleState();
                        if (mIsSupposedToBePlaying) {
                            mIsSupposedToBePlaying = false;
                            notifyChange(PLAYSTATE_CHANGED);
                        }
                        return;
                    }
                }
                int skip = mRand.nextInt(numUnplayed);
                int cnt = -1;
                while (true) {
                    while (tracks[++cnt] < 0)
                        ;
                    skip--;
                    if (skip < 0) {
                        break;
                    }
                }
                mPlayPos = cnt;
            } else if (mShuffleMode == SHUFFLE_AUTO) {
                doAutoShuffleUpdate();
                mPlayPos++;
            } else {
                if (mPlayPos >= mPlayListLen - 1) {
                    // we're at the end of the list
                    if (mRepeatMode == REPEAT_NONE && !force) {
                        // all done
                        gotoIdleState();
                        mIsSupposedToBePlaying = false;
                        notifyChange(PLAYSTATE_CHANGED);
                        return;
                    } else if (mRepeatMode == REPEAT_ALL || force) {
                        mPlayPos = 0;
                    }
                } else {
                    mPlayPos++;
                }
            }
            saveBookmarkIfNeeded();
            stop(false);
            openCurrent();
            play();
            notifyChange(META_CHANGED);
        }
    }

    public void cycleRepeat() {
        if (mRepeatMode == REPEAT_NONE) {
            setRepeatMode(REPEAT_ALL);
        } else if (mRepeatMode == REPEAT_ALL) {
            setRepeatMode(REPEAT_CURRENT);
            if (mShuffleMode != SHUFFLE_NONE) {
                setShuffleMode(SHUFFLE_NONE);
            }
        } else {
            setRepeatMode(REPEAT_NONE);
        }
    }

    public void toggleShuffle() {
        if (mShuffleMode == SHUFFLE_NONE) {
            setShuffleMode(SHUFFLE_NORMAL);
            if (mRepeatMode == REPEAT_CURRENT) {
                setRepeatMode(REPEAT_ALL);
            }
        } else if (mShuffleMode == SHUFFLE_NORMAL || mShuffleMode == SHUFFLE_AUTO) {
            setShuffleMode(SHUFFLE_NONE);
        } else {
            Log.e("MediaPlaybackService", "Invalid shuffle mode: " + mShuffleMode);
        }
    }

    private void gotoIdleState() {
        mDelayedStopHandler.removeCallbacksAndMessages(null);
        Message msg = mDelayedStopHandler.obtainMessage();
        mDelayedStopHandler.sendMessageDelayed(msg, IDLE_DELAY);
        stopForeground(true);
    }

    private void saveBookmarkIfNeeded() {
        try {
            if (isPodcast()) {
                long pos = position();
                long bookmark = getBookmark();
                long duration = duration();
                if ((pos < bookmark && (pos + 10000) > bookmark) ||
                        (pos > bookmark && (pos - 10000) < bookmark)) {
                    // The existing bookmark is close to the current
                    // position, so don't update it.
                    return;
                }
                if (pos < 15000 || (pos + 10000) > duration) {
                    // if we're near the start or end, clear the bookmark
                    pos = 0;
                }

                // write 'pos' to the bookmark field
                ContentValues values = new ContentValues();
                values.put(MediaStore.Audio.Media.BOOKMARK, pos);
                Uri uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mCursor.getLong(IDCOLIDX));
                getContentResolver().update(uri, values, null, null);
            }
        } catch (SQLiteException ex) {
        }
    }

    // Make sure there are at least 5 items after the currently playing item
    // and no more than 10 items before.
    private void doAutoShuffleUpdate() {
        boolean notify = false;

        // remove old entries
        if (mPlayPos > 10) {
            removeTracks(0, mPlayPos - 9);
            notify = true;
        }
        // add new entries if needed
        int to_add = 7 - (mPlayListLen - (mPlayPos < 0 ? -1 : mPlayPos));
        for (int i = 0; i < to_add; i++) {
            // pick something at random from the list

            int lookback = mHistory.size();
            int idx = -1;
            while(true) {
                idx = mRand.nextInt(mAutoShuffleList.length);
                if (!wasRecentlyUsed(idx, lookback)) {
                    break;
                }
                lookback /= 2;
            }
            mHistory.add(idx);
            if (mHistory.size() > MAX_HISTORY_SIZE) {
                mHistory.remove(0);
            }
            ensurePlayListCapacity(mPlayListLen + 1);
            mPlayList[mPlayListLen++] = mAutoShuffleList[idx];
            notify = true;
        }
        if (notify) {
            notifyChange(QUEUE_CHANGED);
        }
    }

    // check that the specified idx is not in the history (but only look at at
    // most lookbacksize entries in the history)
    private boolean wasRecentlyUsed(int idx, int lookbacksize) {

        // early exit to prevent infinite loops in case idx == mPlayPos
        if (lookbacksize == 0) {
            return false;
        }

        int histsize = mHistory.size();
        if (histsize < lookbacksize) {
            Log.d(LOGTAG, "lookback too big");
            lookbacksize = histsize;
        }
        int maxidx = histsize - 1;
        for (int i = 0; i < lookbacksize; i++) {
            long entry = mHistory.get(maxidx - i);
            if (entry == idx) {
                return true;
            }
        }
        return false;
    }

    // A simple variation of Random that makes sure that the
    // value it returns is not equal to the value it returned
    // previously, unless the interval is 1.
    private static class Shuffler {
        private int mPrevious;
        private Random mRandom = new Random();
        public int nextInt(int interval) {
            int ret;
            do {
                ret = mRandom.nextInt(interval);
            } while (ret == mPrevious && interval > 1);
            mPrevious = ret;
            return ret;
        }
    };

    private boolean makeAutoShuffleList() {
        ContentResolver res = getContentResolver();
        Cursor c = null;
        try {
            c = res.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[] {MediaStore.Audio.Media._ID}, MediaStore.Audio.Media.IS_MUSIC + "=1",
                    null, null);
            if (c == null || c.getCount() == 0) {
                return false;
            }
            int len = c.getCount();
            long [] list = new long[len];
            for (int i = 0; i < len; i++) {
                c.moveToNext();
                list[i] = c.getLong(0);
            }
            mAutoShuffleList = list;
            return true;
        } catch (RuntimeException ex) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return false;
    }

    /**
     * Removes the range of tracks specified from the play list. If a file within the range is
     * the file currently being played, playback will move to the next file after the
     * range. 
     * @param first The first file to be removed
     * @param last The last file to be removed
     * @return the number of tracks deleted
     */
    public int removeTracks(int first, int last) {
        int numremoved = removeTracksInternal(first, last);
        if (numremoved > 0) {
            notifyChange(QUEUE_CHANGED);
        }
        return numremoved;
    }

    private int removeTracksInternal(int first, int last) {
        synchronized (this) {
            if (last < first) return 0;
            if (first < 0) first = 0;
            if (last >= mPlayListLen) last = mPlayListLen - 1;

            boolean gotonext = false;
            if (first <= mPlayPos && mPlayPos <= last) {
                mPlayPos = first;
                gotonext = true;
            } else if (mPlayPos > last) {
                mPlayPos -= (last - first + 1);
            }
            int num = mPlayListLen - last - 1;
            for (int i = 0; i < num; i++) {
                mPlayList[first + i] = mPlayList[last + 1 + i];
            }
            mPlayListLen -= last - first + 1;
            
            if (gotonext) {
                if (mPlayListLen == 0) {
                    stop(true);
                    mPlayPos = -1;
                    if (mCursor != null) {
                        mCursor.close();
                        mCursor = null;
                    }
                } else {
                    if (mPlayPos >= mPlayListLen) {
                        mPlayPos = 0;
                    }
                    boolean wasPlaying = isPlaying();
                    stop(false);
                    openCurrent();
                    if (wasPlaying) {
                        play();
                    }
                }
                notifyChange(META_CHANGED);
            }
            return last - first + 1;
        }
    }

    /**
     * Removes all instances of the track with the given id
     * from the playlist.
     * @param id The id to be removed
     * @return how many instances of the track were removed
     */
    public int removeTrack(long id) {
        int numremoved = 0;
        synchronized (this) {
            for (int i = 0; i < mPlayListLen; i++) {
                if (mPlayList[i] == id) {
                    numremoved += removeTracksInternal(i, i);
                    i--;
                }
            }
        }
        if (numremoved > 0) {
            notifyChange(QUEUE_CHANGED);
        }
        return numremoved;
    }

    public void setShuffleMode(int shufflemode) {
        synchronized(this) {
            if (mShuffleMode == shufflemode && mPlayListLen > 0) {
                return;
            }
            mShuffleMode = shufflemode;
            notifyChange(SHUFFLEMODE_CHANGED);
            if (mShuffleMode == SHUFFLE_AUTO) {
                if (makeAutoShuffleList()) {
                    mPlayListLen = 0;
                    doAutoShuffleUpdate();
                    mPlayPos = 0;
                    openCurrent();
                    play();
                    notifyChange(META_CHANGED);
                    return;
                } else {
                    // failed to build a list of files to shuffle
                    mShuffleMode = SHUFFLE_NONE;
                }
            }
            saveQueue(false);
        }
    }

    public int getShuffleMode() {
        return mShuffleMode;
    }

    public void setRepeatMode(int repeatmode) {
        synchronized(this) {
            mRepeatMode = repeatmode;
            notifyChange(REPEATMODE_CHANGED);
            saveQueue(false);
        }
    }

    public int getRepeatMode() {
        return mRepeatMode;
    }

    public int getMediaMountedCount() {
        return mMediaMountedCount;
    }

    /**
     * Returns the path of the currently playing file, or null if
     * no file is currently playing.
     */
    public String getPath() {
        return mFileToPlay;
    }

    /**
     * Returns the rowid of the currently playing file, or -1 if
     * no file is currently playing.
     */
    public long getAudioId() {
        synchronized (this) {
            if (mPlayPos >= 0 && mPlayer.isInitialized()) {
                return mPlayList[mPlayPos];
            }
        }
        return -1;
    }

    /**
     * Returns the position in the queue 
     * @return the position in the queue
     */
    public int getQueuePosition() {
        synchronized(this) {
            return mPlayPos;
        }
    }

    /**
     * Starts playing the track at the given position in the queue.
     * @param pos The position in the queue of the track that will be played.
     */
    public void setQueuePosition(int pos) {
        synchronized(this) {
            stop(false);
            mPlayPos = pos;
            openCurrent();
            play();
            notifyChange(META_CHANGED);
            if (mShuffleMode == SHUFFLE_AUTO) {
                doAutoShuffleUpdate();
            }
        }
    }

    public String getArtistName() {
        synchronized(this) {
            if (mCursor == null) {
                return null;
            }
            return mCursor.getString(mCursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST));
        }
    }

    public long getArtistId() {
        synchronized (this) {
            if (mCursor == null) {
                return -1;
            }
            return mCursor.getLong(mCursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID));
        }
    }

    public String getAlbumartistName() {
        synchronized(this) {
            if (mCursor == null) {
                return null;
            }
            return mCursor.getString(mCursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ARTIST));
        }
    }

    public long getAlbumartistId() {
        synchronized (this) {
            if (mCursor == null) {
                return -1;
            }
            return mCursor.getLong(mCursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ARTIST_ID));
        }
    }

    public String getAlbumName() {
        synchronized (this) {
            if (mCursor == null) {
                return null;
            }
            return mCursor.getString(mCursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM));
        }
    }

    public long getAlbumId() {
        synchronized (this) {
            if (mCursor == null) {
                return -1;
            }
            return mCursor.getLong(mCursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID));
        }
    }

    public String getTrackName() {
        synchronized (this) {
            if (mCursor == null) {
                return null;
            }
            return mCursor.getString(mCursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE));
        }
    }

    private boolean isPodcast() {
        synchronized (this) {
            if (mCursor == null) {
                return false;
            }
            return (mCursor.getInt(PODCASTCOLIDX) > 0);
        }
    }

    private long getBookmark() {
        synchronized (this) {
            if (mCursor == null) {
                return 0;
            }
            return mCursor.getLong(BOOKMARKCOLIDX);
        }
    }

    /**
     * Returns the duration of the file in milliseconds.
     * Currently this method returns -1 for the duration of MIDI files.
     */
    public long duration() {
        if (mPlayer.isInitialized()) {
            return mPlayer.duration();
        }
        return -1;
    }

    /**
     * Returns the current playback position in milliseconds
     */
    public long position() {
        if (mPlayer.isInitialized()) {
            return mPlayer.position();
        }
        return -1;
    }

    /**
     * Seeks to the position specified.
     *
     * @param pos The position to seek to, in milliseconds
     */
    public long seek(long pos) {
        if (mPlayer.isInitialized()) {
            if (pos < 0) pos = 0;
            if (pos > mPlayer.duration()) pos = mPlayer.duration();
            long result = mPlayer.seek(pos);
            fadeUp();
            return result;
        }
        return -1;
    }

    /**
     * Sets the audio session ID.
     *
     * @param sessionId: the audio session ID.
     */
    public void setAudioSessionId(int sessionId) {
        synchronized (this) {
            mPlayer.setAudioSessionId(sessionId);
        }
    }

    /**
     * Returns the audio session ID.
     */
    public int getAudioSessionId() {
        synchronized (this) {
            return mPlayer.getAudioSessionId();
        }
    }

    /**
     * Provides a unified interface for dealing with midi files and
     * other media files.
     */
    private class MultiPlayer {
        private MediaPlayer mMediaPlayer = new MediaPlayer();
        private Handler mHandler;
        private boolean mIsInitialized = false;

        public MultiPlayer() {
            mMediaPlayer.setWakeMode(MediaPlaybackService.this, PowerManager.PARTIAL_WAKE_LOCK);
        }

        public void setDataSource(String path) {
            try {
                mMediaPlayer.reset();
                mMediaPlayer.setOnPreparedListener(null);
                if (path.startsWith("content://")) {
                    mMediaPlayer.setDataSource(MediaPlaybackService.this, Uri.parse(path));
                } else {
                    mMediaPlayer.setDataSource(path);
                }
                mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
                mMediaPlayer.prepare();
            } catch (IOException ex) {
                // TODO: notify the user why the file couldn't be opened
                mIsInitialized = false;
                return;
            } catch (IllegalArgumentException ex) {
                // TODO: notify the user why the file couldn't be opened
                mIsInitialized = false;
                return;
            }
            mMediaPlayer.setOnCompletionListener(listener);
            mMediaPlayer.setOnErrorListener(errorListener);
            Intent i = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, getAudioSessionId());
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
            sendBroadcast(i);
            mIsInitialized = true;
        }

        public boolean isInitialized() {
            return mIsInitialized;
        }

        public void start() {
            MusicUtils.debugLog(new Exception("MultiPlayer.start called"));
            mMediaPlayer.start();
        }

        public void stop() {
            mMediaPlayer.reset();
            mIsInitialized = false;
        }

        /**
         * You CANNOT use this player anymore after calling release()
         */
        public void release() {
            stop();
            mMediaPlayer.release();
        }

        public void pause() {
            mMediaPlayer.pause();
        }

        public void setHandler(Handler handler) {
            mHandler = handler;
        }

        MediaPlayer.OnCompletionListener listener = new MediaPlayer.OnCompletionListener() {
            public void onCompletion(MediaPlayer mp) {
                // Acquire a temporary wakelock, since when we return from
                // this callback the MediaPlayer will release its wakelock
                // and allow the device to go to sleep.
                // This temporary wakelock is released when the RELEASE_WAKELOCK
                // message is processed, but just in case, put a timeout on it.
                mWakeLock.acquire(30000);
                mHandler.sendEmptyMessage(MediaplayerHandler.MESSAGE_TRACK_ENDED);
                mHandler.sendEmptyMessage(MediaplayerHandler.MESSAGE_RELEASE_WAKELOCK);
            }
        };

        MediaPlayer.OnErrorListener errorListener = new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer mp, int what, int extra) {
                switch (what) {
                case MediaPlayer.MEDIA_ERROR_SERVER_DIED:
                    mIsInitialized = false;
                    mMediaPlayer.release();
                    // Creating a new MediaPlayer and settings its wakemode does not
                    // require the media service, so it's OK to do this now, while the
                    // service is still being restarted
                    mMediaPlayer = new MediaPlayer(); 
                    mMediaPlayer.setWakeMode(MediaPlaybackService.this, PowerManager.PARTIAL_WAKE_LOCK);
                    mHandler.sendMessageDelayed(mHandler.obtainMessage(MediaplayerHandler.MESSAGE_SERVER_DIED), 2000);
                    return true;
                default:
                    Log.d("MultiPlayer", "Error: " + what + "," + extra);
                    break;
                }
                return false;
            }
        };

        public long duration() {
            return mMediaPlayer.getDuration();
        }

        public long position() {
            return mMediaPlayer.getCurrentPosition();
        }

        public long seek(long whereto) {
            mMediaPlayer.seekTo((int) whereto);
            return whereto;
        }

        public void setVolume(float vol) {
            mMediaPlayer.setVolume(vol, vol);
            mCurrentVolume = vol;
        }

        public void setAudioSessionId(int sessionId) {
            mMediaPlayer.setAudioSessionId(sessionId);
        }

        public int getAudioSessionId() {
            return mMediaPlayer.getAudioSessionId();
        }
    }

    /*
     * By making this a static class with a WeakReference to the Service, we
     * ensure that the Service can be GCd even when the system process still
     * has a remote reference to the stub.
     */
    static class ServiceStub extends IMediaPlaybackService.Stub {
        WeakReference<MediaPlaybackService> mService;

        ServiceStub(MediaPlaybackService service) {
            mService = new WeakReference<MediaPlaybackService>(service);
        }

        public void openFile(String path)
        {
            mService.get().open(path);
        }
        public void open(long [] list, int position) {
            mService.get().open(list, position);
        }
        public int getQueuePosition() {
            return mService.get().getQueuePosition();
        }
        public void setQueuePosition(int index) {
            mService.get().fadeDownAndSetQueuePosition(index);
        }
        public boolean isPlaying() {
            return mService.get().isPlaying();
        }
        public void stop() {
            mService.get().fadeDownAndStop();
        }
        public void pause() {
            mService.get().fadeDownAndPause();
        }
        public void play() {
            mService.get().play();
        }
        public void prev() {
            mService.get().fadeDownAndPrev();
        }
        public void next() {
            mService.get().fadeDownAndNext();
        }
        public void cycleRepeat() {
            mService.get().cycleRepeat();
        }
        public void toggleShuffle() {
            mService.get().toggleShuffle();
        }
        public String getTrackName() {
            return mService.get().getTrackName();
        }
        public String getAlbumName() {
            return mService.get().getAlbumName();
        }
        public long getAlbumId() {
            return mService.get().getAlbumId();
        }
        public String getArtistName() {
            return mService.get().getArtistName();
        }
        public long getArtistId() {
            return mService.get().getArtistId();
        }
        public String getAlbumartistName() {
            return mService.get().getAlbumartistName();
        }
        public long getAlbumartistId() {
            return mService.get().getAlbumartistId();
        }
        public void enqueue(long [] list , int action) {
            mService.get().enqueue(list, action);
        }
        public long [] getQueue() {
            return mService.get().getQueue();
        }
        public void moveQueueItem(int from, int to) {
            mService.get().moveQueueItem(from, to);
        }
        public String getPath() {
            return mService.get().getPath();
        }
        public long getAudioId() {
            return mService.get().getAudioId();
        }
        public long position() {
            return mService.get().position();
        }
        public long duration() {
            return mService.get().duration();
        }
        public long seek(long pos) {
            // try to avoid jumping of seekbar, not always successful
            // but there's no way to fade and return the correct position immediately
            long currentPos = mService.get().position();
            mService.get().fadeDownAndSeek(pos);
            return currentPos;
        }
        public void setShuffleMode(int shufflemode) {
            mService.get().setShuffleMode(shufflemode);
        }
        public int getShuffleMode() {
            return mService.get().getShuffleMode();
        }
        public int removeTracks(int first, int last) {
            return mService.get().removeTracks(first, last);
        }
        public int removeTrack(long id) {
            return mService.get().removeTrack(id);
        }
        public void setRepeatMode(int repeatmode) {
            mService.get().setRepeatMode(repeatmode);
        }
        public int getRepeatMode() {
            return mService.get().getRepeatMode();
        }
        public int getMediaMountedCount() {
            return mService.get().getMediaMountedCount();
        }
        public int getAudioSessionId() {
            return mService.get().getAudioSessionId();
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println("" + mPlayListLen + " items in queue, currently at index " + mPlayPos);
        writer.println("Currently loaded:");
        writer.println(getArtistName());
        writer.println(getAlbumartistName());
        writer.println(getAlbumName());
        writer.println(getTrackName());
        writer.println(getPath());
        writer.println("playing: " + mIsSupposedToBePlaying);
        writer.println("actual: " + mPlayer.mMediaPlayer.isPlaying());
        writer.println("shuffle mode: " + mShuffleMode);
        MusicUtils.debugDump(writer);
    }

    private final IBinder mBinder = new ServiceStub(this);

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {
		// TODO Auto-generated method stub

	}

	public void onSensorChanged(SensorEvent event) {

		SharedPreferences preferences = getSharedPreferences(
				MusicSettingsActivity.PREFERENCES_FILE, MODE_PRIVATE);
		mPreferences.getBoolean(MusicSettingsActivity.KEY_FLIP, false);

		SharedPreferences mPrefs = PreferenceManager
				.getDefaultSharedPreferences(this);

		int flipChange = new Integer(mPrefs.getInt(
				MusicSettingsActivity.FLIP_SENSITIVITY,
				MusicSettingsActivity.DEFAULT_FLIP_SENS));

		FLIP_SENS = flipChange;

		float vals[] = event.values;
		PITCH = vals[1];// Pitch
		ROLL = vals[2];// Roll

		int nPITCH_UPER = PITCH_UPER;
		int nPITCH_LOVER = PITCH_LOVER;
		int nROLL_UPER = ROLL_UPER;
		int nROLL_LOVER = ROLL_LOVER;

		if (FLIP_SENS != 0) {
			nPITCH_UPER = PITCH_UPER - FLIP_SENS;
			nPITCH_LOVER = PITCH_LOVER + FLIP_SENS;
			nROLL_UPER = ROLL_UPER + FLIP_SENS;
			nROLL_LOVER = ROLL_LOVER - FLIP_SENS;
		}
		if (preferences.getBoolean(MusicSettingsActivity.KEY_FLIP, false)) {
			if (PITCH > nPITCH_UPER || PITCH < nPITCH_LOVER) {
				if (ROLL < nROLL_UPER && ROLL > nROLL_LOVER) {
					if (isPlaying()) {
						pause();
						IsWorked = true;
					}
				} else if (PITCH > nPITCH_UPER || PITCH < nPITCH_LOVER) {
					if (IsWorked) {
						if (!isPlaying()) {
							play();
							IsWorked = false;
						}
					}
				}
			}
		}
	}

	public static void setSensivity(int sensivity) {
		FLIP_SENS = sensivity - 0;

	}

	private void doPauseResume() {
		if (isPlaying()) {
			pause();
		} else {
			play();
		}
	}

	private void doNext() {
		next(true);
	}

	private void doPrev() {
		if (position() < 2000) {
			prev();
		} else {
			seek(0);
			play();
		}
	}

	@Override
	public void shakingStarted() {
		SharedPreferences preferences = getSharedPreferences(
				MusicSettingsActivity.PREFERENCES_FILE, MODE_PRIVATE);
		shake_actions_db = preferences.getString("shake_actions_db", "1");
		if (shake_actions_db.equals("1")) {
			doPauseResume();
		}
		shake_actions_db = preferences.getString("shake_actions_db", "2");
		if (shake_actions_db.equals("2")) {
			doNext();
		}
		shake_actions_db = preferences.getString("shake_actions_db", "3");
		if (shake_actions_db.equals("3")) {
			doPrev();
		}
		shake_actions_db = preferences.getString("shake_actions_db", "4");
		if (shake_actions_db.equals("4")) {
			Cursor cursor;
			cursor = MusicUtils.query(this,
					MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
					new String[] { BaseColumns._ID }, AudioColumns.IS_MUSIC
							+ "=1", null,
					MediaStore.Audio.Media.DEFAULT_SORT_ORDER);
			if (cursor != null) {
				MusicUtils.shuffleAll(this, cursor);
				cursor.close();
			}
		}
		shake_actions_db = preferences.getString("shake_actions_db", "5");
		if (shake_actions_db.equals("5")) {
			int shuffle = getShuffleMode();
			if (shuffle == SHUFFLE_AUTO) {
				setShuffleMode(SHUFFLE_NONE);
			} else {
				setShuffleMode(SHUFFLE_AUTO);
			}
			shake_actions_db = preferences.getString("shake_actions_db", "0");
			if (shake_actions_db.equals("0")) {
				// Nothing - this needs to stay last
			}
		}
	}

	@Override
	public void shakingStopped() {

	}
}