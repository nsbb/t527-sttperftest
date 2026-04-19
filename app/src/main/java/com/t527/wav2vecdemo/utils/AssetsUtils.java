package com.t527.wav2vecdemo.utils;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class AssetsUtils {
    private static final String TAG = "AssetsUtils";

    public static boolean copy(Context context, String assetPath, String targetPath) {
        try {
            InputStream inputStream = context.getAssets().open(assetPath);
            File targetFile = new File(targetPath);
            
            // 부모 디렉토리 생성
            File parentDir = targetFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            
            FileOutputStream outputStream = new FileOutputStream(targetFile);
            
            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }
            
            outputStream.close();
            inputStream.close();
            
            Log.d(TAG, "Successfully copied " + assetPath + " to " + targetPath);
            return true;
            
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy " + assetPath, e);
            return false;
        }
    }
}