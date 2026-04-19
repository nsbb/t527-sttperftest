package com.t527.wav2vecdemo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class SttBroadcastReceiver extends BroadcastReceiver {
    private static final String TAG = "SttBroadcastReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "BR received: " + action);
        Intent serviceIntent = new Intent(context, VadPipelineService.class);
        serviceIntent.setAction(action);
        context.startForegroundService(serviceIntent);
    }
}
