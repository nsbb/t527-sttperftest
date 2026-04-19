package com.t527.wav2vecdemo;

import android.util.Log;

/**
 * Direct ALSA PCM capture via JNI (libtinyalsa), bypassing AudioFlinger silencing.
 *
 * On new T527 wallpads, system services (com.hdclabs.wallpadserver, com.android.localtransport,
 * com.android.inputdevices) permanently hold UNPROCESSED mic capture (uid:1000),
 * causing all AudioRecord sessions from third-party apps to be silenced (data = zeros).
 *
 * This class opens pcmC0D0c directly at the ALSA level, which the T527 driver allows
 * concurrently with AudioFlinger. pcmC0D0c has world-accessible permissions (crwxrwxrwx).
 *
 * Card 0, Device 0: sunxi-snd-plat-aaudio-sunxi-snd-codec (physical mic)
 */
public class AlsaCapture {

    private static final String TAG = "AlsaCapture";

    // T527 physical mic: card=0, device=0
    public static final int CARD = 0;
    public static final int DEVICE = 0;

    static {
        System.loadLibrary("awconformer");
    }

    private long mHandle = 0;
    private final int mRate;
    private final int mChannels;
    private final int mPeriodSize;

    public AlsaCapture(int rate, int channels, int periodSize) {
        mRate = rate;
        mChannels = channels;
        mPeriodSize = periodSize;
    }

    /**
     * Open the ALSA capture device.
     * @return true on success
     */
    public boolean open() {
        mHandle = nativeOpen(CARD, DEVICE, mRate, mChannels, mPeriodSize, 4);
        if (mHandle == 0) {
            Log.e(TAG, "ALSA pcmC" + CARD + "D" + DEVICE + "c open failed");
            return false;
        }
        Log.d(TAG, "ALSA pcmC" + CARD + "D" + DEVICE + "c opened: "
                + mRate + "Hz " + mChannels + "ch period=" + mPeriodSize);
        return true;
    }

    /**
     * Read PCM samples (S16_LE) into the provided short array.
     * @param buf    destination buffer
     * @param offset start offset in buf (samples)
     * @param count  number of samples to read
     * @return number of samples read, or -1 on error
     */
    public int read(short[] buf, int offset, int count) {
        if (mHandle == 0) return -1;
        return nativeRead(mHandle, buf, offset, count);
    }

    /**
     * Close the ALSA capture device.
     */
    public void close() {
        if (mHandle != 0) {
            nativeClose(mHandle);
            mHandle = 0;
        }
    }

    public boolean isOpen() {
        return mHandle != 0;
    }

    // --- native methods ---
    private static native long nativeOpen(int card, int device, int rate, int channels,
                                          int periodSize, int periodCount);
    private static native int  nativeRead(long handle, short[] buf, int offset, int numSamples);
    private static native void nativeClose(long handle);
}
