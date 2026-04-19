package com.t527.wav2vecdemo;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

public class VadPipelineActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 마이크 권한 확인
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }

        startServiceAndFinish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startServiceAndFinish();
        } else {
            Toast.makeText(this, "마이크 권한 필요", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void startServiceAndFinish() {
        Intent intent = new Intent(this, VadPipelineService.class);
        intent.setAction(VadPipelineService.ACTION_MIC_GRANTED);
        startForegroundService(intent);
        Toast.makeText(this, "음성 AI 서비스 시작", Toast.LENGTH_SHORT).show();
        finish();
    }
}
