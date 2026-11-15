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

package com.android.fmradio;

import android.media.AudioDevicePort;
import android.media.AudioDevicePortConfig;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioManager.OnAudioPortUpdateListener;
import android.media.AudioMixPort;
import android.media.AudioPatch;
import android.media.AudioPort;
import android.media.AudioPortConfig;
import android.media.AudioRecord;
import android.media.AudioSystem;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Process;
import android.util.Log;
import java.util.ArrayList;
import android.os.SystemProperties;

final class FmAudio {
    static final String STATION_AUTHORITY = "com.android.fmradio";
    static final boolean REQUIRES_RECORD_AUDIO_PERMISSION = true;
    static final boolean RECORDING_SUPPORTED = true;
    private static final String TAG = "FmAudio";
    private static final int FOR_PROPRIETARY = 1;
    private static final int AUDIO_FRAMES_TO_IGNORE_COUNT = 3;

    private final FmService service;
    private final AudioManager mAudioManager;
    private final Object mRenderLock = new Object();
    private AudioPatch mAudioPatch;
    private int mForcedUseForMedia;

    FmAudio(FmService service, AudioManager audioManager) {
        this.service = service;
        mAudioManager = audioManager;
    }

    static boolean hasBuiltInAntenna() {
        return "1".equals(SystemProperties.get("ro.vendor.builtin_fm_antenna_support"));
    }

    void init(boolean speaker) {
        registerAudioPortUpdateListener();
        setSpeaker(speaker);
        initAudioRecordSink();
        createRenderThread();
    }

    void release() {
        stopRender();
        exitRenderThread();
        releaseAudioPatch();
        releaseAudioRecorder();
        releaseAudioTrack();
        unregisterAudioPortUpdateListener();
    }

    boolean isCaptureStopped() {
        return false;
    }

    void setPreStop(boolean enabled) {
        mAudioManager.setParameters("AudioFmPreStop=" + (enabled ? "1" : "0"));
    }

    void forceHeadset() {
        AudioSystem.setForceUse(FOR_PROPRIETARY, AudioSystem.FORCE_NONE);
    }

    AudioFormat recordingFormat() {
        return mAudioRecord.getFormat();
    }

    void onRecordingStarted() {
        if (mAudioPatch != null) {
            releaseAudioPatch();
            startRender();
        }
    }

    void onRecordingStopped() {
        if (mAudioPatch == null) startPatchOrRender();
    }

    void setSpeaker(boolean isSpeaker) {
        mForcedUseForMedia = isSpeaker ? AudioSystem.FORCE_SPEAKER : AudioSystem.FORCE_NONE;
        AudioSystem.setForceUse(FOR_PROPRIETARY, mForcedUseForMedia);
    }

    private synchronized void startRender() {
        Log.d(TAG, "startRender " + AudioSystem.getForceUse(FOR_PROPRIETARY));

        exitRenderThread();

       // need to create new audio record and audio play back track,
       // because input/output device may be changed.
       releaseAudioRecorder();
       releaseAudioTrack();
       initAudioRecordSink();

        mIsRender = true;
        createRenderThread();
        synchronized (mRenderLock) {
            mRenderLock.notify();
        }
    }

    private synchronized void stopRender() {
        Log.d(TAG, "stopRender");
        mIsRender = false;
        // HACK: Set volume to 0 to squelch any output between the call to
        // stopRender and the render thread calling AudioTrack.stop
        if (mAudioTrack != null) mAudioTrack.setVolume(0.0f);
    }

    private synchronized void createRenderThread() {
        if (mRenderThread == null) {
            mRenderThread = new RenderThread();
            mRenderThread.start();
        }
    }

    private synchronized void exitRenderThread() {
        if (mRenderThread == null) return;
        mRenderThread.interrupt();
        try {
            mRenderThread.join();
        } catch (InterruptedException ie) {
            Log.e(TAG, "Failed to join render thread");
        }
        mRenderThread = null;
    }

    private Thread mRenderThread = null;
    private AudioRecord mAudioRecord = null;
    private AudioTrack mAudioTrack = null;
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_CONFIGURATION_STEREO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int RECORD_BUF_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE,
            CHANNEL_CONFIG, AUDIO_FORMAT);
    private boolean mIsRender = false;

    AudioDevicePort mAudioSource = null;
    AudioDevicePort mAudioSink = null;

    private boolean isRendering() {
        return mIsRender;
    }

    private void startAudioTrack() {
        if (mAudioTrack.getPlayState() == AudioTrack.PLAYSTATE_STOPPED) {
            ArrayList<AudioPatch> patches = new ArrayList<AudioPatch>();
            mAudioManager.listAudioPatches(patches);
            mAudioTrack.play();
        }
    }

    private void stopAudioTrack() {
        if (mAudioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
            mAudioTrack.stop();
        }
    }

    class RenderThread extends Thread {
        private int mCurrentFrame = 0;
        private boolean isAudioFrameNeedIgnore() {
            return mCurrentFrame < AUDIO_FRAMES_TO_IGNORE_COUNT;
        }

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            try {
                byte[] buffer = new byte[RECORD_BUF_SIZE];
                while (!Thread.interrupted()) {
                    if (isRender()) {
                        // Speaker mode or BT a2dp mode will come here and keep reading and writing.
                        // If we want FM sound output from speaker or BT a2dp, we must record data
                        // to AudioRecrd and write data to AudioTrack.
                        if (mAudioRecord.getRecordingState() == AudioRecord.RECORDSTATE_STOPPED) {
                            mAudioRecord.startRecording();
                        }

                        if (mAudioTrack.getPlayState() == AudioTrack.PLAYSTATE_STOPPED) {
                            mAudioTrack.play();
                        }
                        int size = mAudioRecord.read(buffer, 0, RECORD_BUF_SIZE);
                        // check whether need to ignore first 3 frames audio data from AudioRecord
                        // to avoid pop noise.
                        if (isAudioFrameNeedIgnore()) {
                            mCurrentFrame += 1;
                            continue ;
                        }
                        if (size <= 0) {
                            Log.e(TAG, "RenderThread read data from AudioRecord "
                                    + "error size: " + size);
                            continue;
                        }
                        byte[] tmpBuf = new byte[size];
                        System.arraycopy(buffer, 0, tmpBuf, 0, size);
                        // Check again to avoid noises, because mIsRender may be changed
                        // while AudioRecord is reading.
                        if (isRender()) {
                            mAudioTrack.write(tmpBuf, 0, tmpBuf.length);
                        }

                        FmRecorder recorder = service.getActiveRecorder();
                        if (recorder != null) {
                            recorder.encode(tmpBuf);
                        }
                    } else {
                        // Earphone mode will come here and wait.
                        mCurrentFrame = 0;

                        if (mAudioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                            mAudioTrack.pause();
                            mAudioTrack.flush();
                            mAudioTrack.stop();
                        }

                        if (mAudioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                            mAudioRecord.stop();
                        }

                        synchronized (mRenderLock) {
                            mRenderLock.wait();
                        }
                    }
                }
            } catch (InterruptedException e) {
                Log.d(TAG, "RenderThread.run, thread is interrupted, need exit thread");
            } finally {
                if (mAudioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    mAudioRecord.stop();
                }
                if (mAudioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    mAudioTrack.stop();
                }
            }
        }
    }

    // A2dp or speaker mode should render
    private boolean isRender() {
        return (mIsRender && service.canRenderAudio());
    }

    boolean isSpeaker() {
        return (mForcedUseForMedia == AudioSystem.FORCE_SPEAKER);
    }

    private void registerAudioPortUpdateListener() {
        if (mAudioPortUpdateListener == null) {
            mAudioPortUpdateListener = new FmOnAudioPortUpdateListener();
            mAudioManager.registerAudioPortUpdateListener(mAudioPortUpdateListener);
        }
    }

    private void unregisterAudioPortUpdateListener() {
        if (mAudioPortUpdateListener != null) {
            mAudioManager.unregisterAudioPortUpdateListener(mAudioPortUpdateListener);
            mAudioPortUpdateListener = null;
        }
    }

    // This function may be called in different threads.
    // Need to add "synchronized" to make sure mAudioRecord and mAudioTrack are the newest.
    // Thread 1: onCreate() or startRender()
    // Thread 2: onAudioPatchListUpdate() or startRender()
    private synchronized void initAudioRecordSink() {
        if (mAudioRecord == null) {
            mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.RADIO_TUNER,
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, RECORD_BUF_SIZE);
        }
        if (mAudioTrack == null) {
            mAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE, CHANNEL_CONFIG,
                    AUDIO_FORMAT, RECORD_BUF_SIZE, AudioTrack.MODE_STREAM);
        }
    }

    private synchronized int createAudioPatch() {
        Log.d(TAG, "createAudioPatch");
        int status = AudioManager.SUCCESS;
        if (mAudioPatch != null) {
            Log.d(TAG, "createAudioPatch, mAudioPatch is not null, return");
            return status;
        }

        mAudioSource = null;
        mAudioSink = null;
        ArrayList<AudioPort> ports = new ArrayList<AudioPort>();
        mAudioManager.listAudioPorts(ports);
        for (AudioPort port : ports) {
            if (port instanceof AudioDevicePort) {
                int type = ((AudioDevicePort) port).type();
                String name = AudioSystem.getOutputDeviceName(type);
                if (type == AudioSystem.DEVICE_IN_FM_TUNER) {
                    mAudioSource = (AudioDevicePort) port;
                } else if (type == AudioSystem.DEVICE_OUT_WIRED_HEADSET ||
                        type == AudioSystem.DEVICE_OUT_WIRED_HEADPHONE) {
                    mAudioSink = (AudioDevicePort) port;
                }
            }
        }
        if (mAudioSource != null && mAudioSink != null) {
            AudioDevicePortConfig sourceConfig = (AudioDevicePortConfig) mAudioSource
                    .activeConfig();
            AudioDevicePortConfig sinkConfig = (AudioDevicePortConfig) mAudioSink.activeConfig();
            AudioPatch[] audioPatchArray = new AudioPatch[] {null};
            status = mAudioManager.createAudioPatch(audioPatchArray,
                    new AudioPortConfig[] {sourceConfig},
                    new AudioPortConfig[] {sinkConfig});
            mAudioPatch = audioPatchArray[0];
        }
        return status;
    }

    private FmOnAudioPortUpdateListener mAudioPortUpdateListener = null;

    private class FmOnAudioPortUpdateListener implements OnAudioPortUpdateListener {
        /**
         * Callback method called upon audio port list update.
         * @param portList the updated list of audio ports
         */
        @Override
        public void onAudioPortListUpdate(AudioPort[] portList) {
            // Ingore audio port update
        }

        /**
         * Callback method called upon audio patch list update.
         *
         * @param patchList the updated list of audio patches
         */
        @Override
        public void onAudioPatchListUpdate(AudioPatch[] patchList) {
            if (!service.canRenderAudio()) {
                Log.d(TAG, "onAudioPatchListUpdate, not power up");
                return;
            }

            if (mAudioPatch != null) {
                ArrayList<AudioPatch> patches = new ArrayList<AudioPatch>();
                mAudioManager.listAudioPatches(patches);
                // When BT or WFD is connected, native will remove the patch (mixer -> device).
                // Need to recreate AudioRecord and AudioTrack for this case.
                if (isPatchMixerToDeviceRemoved(patches)) {
                    Log.d(TAG, "onAudioPatchListUpdate reinit for BT or WFD connected");
                    startRender();
                    return;
                }
                if (isPatchMixerToEarphone(patches)) {
                    stopRender();
                } else {
                    releaseAudioPatch();
                    startRender();
                }
            } else if (mIsRender) {
                ArrayList<AudioPatch> patches = new ArrayList<AudioPatch>();
                mAudioManager.listAudioPatches(patches);
                if (isPatchMixerToEarphone(patches)) {
                    int status;
                    stopAudioTrack();
                    stopRender();
                    status = createAudioPatch();
                    if (status != AudioManager.SUCCESS){
                       Log.d(TAG, "onAudioPatchListUpdate: fallback as createAudioPatch failed");
                       startRender();
                    }
                }
            }
        }

        /**
         * Callback method called when the mediaserver dies
         */
        @Override
        public void onServiceDied() {
            setEnabled(false);
        }
    }

    private synchronized void releaseAudioPatch() {
        if (mAudioPatch != null) {
            Log.d(TAG, "releaseAudioPatch");
            mAudioManager.releaseAudioPatch(mAudioPatch);
            mAudioPatch = null;
        }
        mAudioSource = null;
        mAudioSink = null;
    }

    private synchronized void releaseAudioTrack() {
        if (mAudioTrack != null) {
            if (mAudioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                mAudioTrack.stop();
            }
            mAudioTrack.release();
            mAudioTrack = null;
        }
    }

    private synchronized void releaseAudioRecorder() {
        if (mAudioRecord != null) {
            if (mAudioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                mAudioRecord.stop();
            }
            mAudioRecord.release();
            mAudioRecord = null;
        }
    }

    void setEnabled(boolean enable) {
        if (enable) {
            if (!service.canRenderAudio()) {
                return;
            }

            startAudioTrack();
            startPatchOrRender();
        } else {
            releaseAudioPatch();
            stopRender();
        }
    }

    private void startPatchOrRender() {
        ArrayList<AudioPatch> patches = new ArrayList<AudioPatch>();
        mAudioManager.listAudioPatches(patches);
        if (mAudioPatch == null) {
            if (isPatchMixerToEarphone(patches)) {
                int status;
                stopAudioTrack();
                stopRender();
                status = createAudioPatch();
                if (status != AudioManager.SUCCESS){
                   Log.d(TAG, "startPatchOrRender: fallback as createAudioPatch failed");
                   startRender();
                }
            } else {
                if (!isRendering()) {
                    startRender();
                }
            }
        }
    }

    // Make sure patches count will not be 0
    private boolean isPatchMixerToEarphone(ArrayList<AudioPatch> patches) {
        int deviceCount = 0;
        int deviceEarphoneCount = 0;

        if (service.getRecorderState() == FmRecorder.STATE_RECORDING) {
            // force software rendering when recording
            return false;
        }

        if (service.getResources().getBoolean(R.bool.config_useSoftwareRenderingForAudio)) {
            Log.w(TAG, "FIXME: forcing isPatchMixerToEarphone to return false. "
                    + "Software rendering will be used.");
            return false;
        } else {
            for (AudioPatch patch : patches) {
                AudioPortConfig[] sources = patch.sources();
                AudioPortConfig[] sinks = patch.sinks();
                AudioPortConfig sourceConfig = sources[0];
                AudioPortConfig sinkConfig = sinks[0];
                AudioPort sourcePort = sourceConfig.port();
                AudioPort sinkPort = sinkConfig.port();
                Log.d(TAG, "isPatchMixerToEarphone " + sourcePort + " ====> " + sinkPort);
                if (sourcePort instanceof AudioMixPort && sinkPort instanceof AudioDevicePort) {
                    deviceCount++;
                    int type = ((AudioDevicePort) sinkPort).type();
                    if (type == AudioSystem.DEVICE_OUT_WIRED_HEADSET ||
                            type == AudioSystem.DEVICE_OUT_WIRED_HEADPHONE) {
                        deviceEarphoneCount++;
                    }
                }
            }
            if (deviceEarphoneCount == 1 && deviceCount == deviceEarphoneCount) {
                return true;
            }
        }
        return false;
    }

    // Check whether the patch (mixer -> device) is removed by native.
    // If no patch (mixer -> device), return true.
    private boolean isPatchMixerToDeviceRemoved(ArrayList<AudioPatch> patches) {
        boolean noMixerToDevice = true;
        for (AudioPatch patch : patches) {
            AudioPortConfig[] sources = patch.sources();
            AudioPortConfig[] sinks = patch.sinks();
            AudioPortConfig sourceConfig = sources[0];
            AudioPortConfig sinkConfig = sinks[0];
            AudioPort sourcePort = sourceConfig.port();
            AudioPort sinkPort = sinkConfig.port();

            if (sourcePort instanceof AudioMixPort && sinkPort instanceof AudioDevicePort) {
                noMixerToDevice = false;
                break;
            }
        }
        return noMixerToDevice;
    }

}
