package com.t527.wav2vecdemo.utils;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class DanjiServerSender {
    private static final String TAG = "DanjiServerSender";
    private static final String SERVER_URL = "https://10.0.1.1/ai/text/send";

    private static final String DONG = "101";
    private static final String HO = "101";

    private static File sAppLogFile;

    public interface Callback {
        void onResult(boolean success, String message);
    }

    public static void init(Context context) {
        sAppLogFile = new File(context.getFilesDir(), "danji_send.log");
    }

    private static void fileLog(String msg) {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(new Date());
        String line = "[" + ts + "] " + msg + "\n";
        // 앱 내부 저장소
        if (sAppLogFile != null) {
            try {
                FileWriter fw = new FileWriter(sAppLogFile, true);
                fw.write(line);
                fw.close();
            } catch (Exception e) {
                Log.e(TAG, "fileLog(app) failed", e);
            }
        }
        // /data/local/tmp/ (권한 있으면)
        try {
            FileWriter fw2 = new FileWriter(new File("/data/local/tmp/danji_send.log"), true);
            fw2.write(line);
            fw2.close();
        } catch (Exception ignored) {}
    }

    public static void send(String text, Callback callback) {
        new Thread(() -> {
            try {
                String reqid = UUID.randomUUID().toString();
                String boundary = "----Boundary" + System.currentTimeMillis();

                URL url = new URL(SERVER_URL);
                HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();

                // 자체서명 인증서 허용
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, new TrustManager[]{new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] c, String a) {}
                    public void checkServerTrusted(X509Certificate[] c, String a) {}
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }}, new SecureRandom());
                conn.setSSLSocketFactory(sc.getSocketFactory());
                conn.setHostnameVerifier((h, s) -> true);

                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                OutputStream os = conn.getOutputStream();
                writeField(os, boundary, "reqid", reqid);
                writeField(os, boundary, "dong", DONG);
                writeField(os, boundary, "ho", HO);
                writeField(os, boundary, "text", text);
                os.write(("--" + boundary + "--\r\n").getBytes("UTF-8"));
                os.flush();
                os.close();

                int code = conn.getResponseCode();
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(code >= 400 ? conn.getErrorStream() : conn.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                conn.disconnect();

                String body = sb.toString();
                Log.d(TAG, "Response: " + code + " " + body);
                fileLog("SEND reqid=" + reqid + " text=\"" + text + "\" -> HTTP " + code + " " + body);

                if (code == 200) {
                    if (callback != null) callback.onResult(true, body);
                } else {
                    if (callback != null) callback.onResult(false, code + ": " + body);
                }
            } catch (Exception e) {
                Log.e(TAG, "Send failed", e);
                fileLog("FAIL text=\"" + text + "\" error=" + e.getMessage());
                if (callback != null) callback.onResult(false, e.getMessage());
            }
        }).start();
    }

    private static void writeField(OutputStream os, String boundary, String name, String value) throws Exception {
        os.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
        os.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes("UTF-8"));
        os.write((value + "\r\n").getBytes("UTF-8"));
    }
}
