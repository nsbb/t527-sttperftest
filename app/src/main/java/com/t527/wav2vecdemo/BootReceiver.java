package com.t527.wav2vecdemo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d("BootReceiver", "Boot completed, launching Activity for mic grant");
            // Activity 경유해야 silenced:false로 마이크 획득 가능
            Intent actIntent = new Intent(context, VadPipelineActivity.class);
            actIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(actIntent);
        }
    }
}
