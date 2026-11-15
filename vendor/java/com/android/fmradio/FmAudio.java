// SPDX-License-Identifier: Apache-2.0

package com.android.fmradio;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;

import java.io.IOException;

final class FmAudio {
    static final String STATION_AUTHORITY = "org.lineageos.fmradio.vendor";
    static final boolean REQUIRES_RECORD_AUDIO_PERMISSION = false;
    static final boolean RECORDING_SUPPORTED = false;
    private static final String TAG = "FmVendorAudio";
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = AudioFormat.CHANNEL_OUT_STEREO;
    private static final int PCM_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_BYTES = 4096;

    private final FmService service;
    private final AudioManager audioManager;
    private Playback playback;
    private boolean speaker;

    FmAudio(FmService service, AudioManager audioManager) {
        this.service = service;
        this.audioManager = audioManager;
    }

    static boolean hasBuiltInAntenna() {
        return false;
    }

    synchronized void init(boolean speaker) {
        this.speaker = speaker;
    }

    synchronized void setSpeaker(boolean speaker) {
        this.speaker = speaker;
        if (playback != null && playback.track != null) {
            playback.track.setPreferredDevice(preferredOutput());
        }
    }

    synchronized boolean isSpeaker() {
        return speaker;
    }

    void forceHeadset() {
        setSpeaker(false);
    }

    synchronized boolean isCaptureStopped() {
        return playback == null;
    }

    void setPreStop(boolean enabled) {}

    AudioFormat recordingFormat() {
        throw new UnsupportedOperationException("FM recording is unavailable");
    }

    void onRecordingStarted() {}

    void onRecordingStopped() {}

    void release() {
        setEnabled(false);
    }

    synchronized void setEnabled(boolean enabled) {
        if (enabled && service.canRenderAudio()) {
            if (playback == null) {
                playback = new Playback();
                playback.start();
            }
        } else if (playback != null) {
            playback.stopped = true;
            if (playback.track != null) playback.track.setVolume(0);
            playback = null;
        }
    }

    private AudioDeviceInfo preferredOutput() {
        if (speaker) {
            for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return device;
            }
        }
        return null;
    }

    private static native int openAudio();

    private final class Playback extends Thread {
        private volatile boolean stopped;
        private AudioTrack track;

        Playback() {
            super("FM playback");
        }

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            AudioTrack output = null;
            try {
                final int fd;
                synchronized (FmAudio.this) {
                    if (stopped) return;
                    fd = openAudio();
                }
                if (fd < 0) throw new IOException("Cannot open FM capture stream");

                try (ParcelFileDescriptor.AutoCloseInputStream pcm = new ParcelFileDescriptor.AutoCloseInputStream(
                        ParcelFileDescriptor.adoptFd(fd))) {
                    AudioFormat format = new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNELS)
                            .setEncoding(PCM_FORMAT)
                            .build();
                    AudioAttributes attributes = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build();
                    int minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNELS, PCM_FORMAT);
                    output = new AudioTrack.Builder()
                            .setAudioAttributes(attributes)
                            .setAudioFormat(format)
                            .setBufferSizeInBytes(Math.max(minimum, BUFFER_BYTES * 4))
                            .build();
                    synchronized (FmAudio.this) {
                        if (stopped) return;
                        track = output;
                        output.setPreferredDevice(preferredOutput());
                        output.play();
                    }
                    byte[] buffer = new byte[BUFFER_BYTES];
                    while (!stopped) {
                        int bytes = pcm.readNBytes(buffer, 0, buffer.length);
                        if (bytes != buffer.length || stopped) break;
                        if (output.write(buffer, 0, bytes) != bytes) break;
                    }
                }
            } catch (IOException | IllegalStateException | IllegalArgumentException error) {
                if (!stopped) Log.w(TAG, "FM capture ended", error);
            } finally {
                synchronized (FmAudio.this) {
                    track = null;
                    if (output != null) output.release();
                    if (playback == this) {
                        playback = null;
                        service.onFmAudioEnded();
                    }
                }
            }
        }
    }
}
