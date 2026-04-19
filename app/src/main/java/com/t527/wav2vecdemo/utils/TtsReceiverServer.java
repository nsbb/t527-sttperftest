package com.t527.wav2vecdemo.utils;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;

import fi.iki.elonen.NanoHTTPD;

public class TtsReceiverServer extends NanoHTTPD {
    private static final String TAG = "TtsReceiverServer";
    private static final int PORT = 8030;

    private final Context mContext;
    private MediaPlayer mPlayer;

    public TtsReceiverServer(Context context) {
        super(PORT);
        mContext = context.getApplicationContext();
        setupSSL();
    }

    private void setupSSL() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);

            // 자체서명 인증서가 없으면 생성
            File ksFile = new File(mContext.getFilesDir(), "server.bks");
            KeyStore serverKs = KeyStore.getInstance("BKS", "BC");

            if (ksFile.exists()) {
                serverKs.load(new java.io.FileInputStream(ksFile), "changeit".toCharArray());
            } else {
                serverKs.load(null, "changeit".toCharArray());

                // RSA 키페어 생성
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                kpg.initialize(2048);
                java.security.KeyPair kp = kpg.generateKeyPair();

                // 자체서명 X509 인증서 생성
                long now = System.currentTimeMillis();
                Date start = new Date(now);
                Date end = new Date(now + 10L * 365 * 24 * 60 * 60 * 1000); // 10년

                // X509v3 인증서 수동 생성 (Bouncy Castle 없이)
                javax.security.auth.x500.X500Principal owner =
                        new javax.security.auth.x500.X500Principal("CN=wallpad, O=t527");

                // Android에서 사용 가능한 자체서명 방식
                String sigAlg = "SHA256withRSA";
                byte[] encoded = generateSelfSignedCert(kp, owner, start, end, sigAlg);

                java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
                X509Certificate cert = (X509Certificate) cf.generateCertificate(
                        new java.io.ByteArrayInputStream(encoded));

                serverKs.setKeyEntry("server", kp.getPrivate(), "changeit".toCharArray(),
                        new Certificate[]{cert});
                serverKs.store(new FileOutputStream(ksFile), "changeit".toCharArray());
            }

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(serverKs, "changeit".toCharArray());

            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(kmf.getKeyManagers(), null, null);

            makeSecure(sc.getServerSocketFactory(), null);
            Log.d(TAG, "SSL enabled on port " + PORT);
        } catch (Exception e) {
            Log.e(TAG, "SSL setup failed, falling back to HTTP", e);
        }
    }

    private byte[] generateSelfSignedCert(java.security.KeyPair kp,
            javax.security.auth.x500.X500Principal owner, Date start, Date end, String sigAlg) throws Exception {
        // V3 자체서명 인증서를 DER로 직접 생성
        // sun.security.x509 대신 Android 호환 방식
        java.security.Signature sig = java.security.Signature.getInstance(sigAlg);
        sig.initSign(kp.getPrivate());

        // TBSCertificate 구성 (ASN.1 DER)
        byte[] issuerBytes = owner.getEncoded();
        byte[] subjectBytes = owner.getEncoded();
        byte[] pubKeyBytes = kp.getPublic().getEncoded();

        // 간단한 DER 인코딩
        java.io.ByteArrayOutputStream tbs = new java.io.ByteArrayOutputStream();
        // Version: v3
        tbs.write(new byte[]{(byte)0xA0, 0x03, 0x02, 0x01, 0x02});
        // Serial number
        byte[] serial = BigInteger.valueOf(System.currentTimeMillis()).toByteArray();
        tbs.write(derTag(0x02, serial));
        // Signature algorithm (SHA256withRSA OID: 1.2.840.113549.1.1.11)
        byte[] sigAlgOid = new byte[]{0x30, 0x0D, 0x06, 0x09, 0x2A, (byte)0x86, 0x48, (byte)0x86,
                (byte)0xF7, 0x0D, 0x01, 0x01, 0x0B, 0x05, 0x00};
        tbs.write(sigAlgOid);
        // Issuer
        tbs.write(issuerBytes);
        // Validity
        java.io.ByteArrayOutputStream validity = new java.io.ByteArrayOutputStream();
        validity.write(derUtcTime(start));
        validity.write(derUtcTime(end));
        tbs.write(derTag(0x30, validity.toByteArray()));
        // Subject
        tbs.write(subjectBytes);
        // Subject Public Key Info
        tbs.write(pubKeyBytes);

        byte[] tbsBytes = derTag(0x30, tbs.toByteArray());

        // Sign
        sig.update(tbsBytes);
        byte[] sigBytes = sig.sign();
        byte[] sigBitString = derBitString(sigBytes);

        // Full certificate
        java.io.ByteArrayOutputStream cert = new java.io.ByteArrayOutputStream();
        cert.write(tbsBytes);
        cert.write(sigAlgOid);
        cert.write(sigBitString);

        return derTag(0x30, cert.toByteArray());
    }

    private byte[] derTag(int tag, byte[] value) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(tag);
        if (value.length < 128) {
            out.write(value.length);
        } else if (value.length < 256) {
            out.write((byte) 0x81);
            out.write(value.length);
        } else {
            out.write((byte) 0x82);
            out.write((value.length >> 8) & 0xFF);
            out.write(value.length & 0xFF);
        }
        out.write(value, 0, value.length);
        return out.toByteArray();
    }

    private byte[] derBitString(byte[] data) {
        byte[] bs = new byte[data.length + 1];
        bs[0] = 0x00; // no unused bits
        System.arraycopy(data, 0, bs, 1, data.length);
        return derTag(0x03, bs);
    }

    private byte[] derUtcTime(Date date) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        byte[] timeBytes = sdf.format(date).getBytes();
        return derTag(0x17, timeBytes);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();
        Log.d(TAG, method + " " + uri);

        if (Method.POST.equals(method) && "/agent/ai/result".equals(uri)) {
            return handleAiResult(session);
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json",
                "{\"result\":\"not found\"}");
    }

    private Response handleAiResult(IHTTPSession session) {
        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);

            Map<String, String> params = session.getParms();
            String sttText = params.get("stt");
            String ttsFilePath = files.get("tts");

            Log.d(TAG, "Received stt=\"" + sttText + "\", tts file=" + ttsFilePath);
            fileLog("RECV stt=\"" + sttText + "\" ttsFile=" + ttsFilePath);

            if (ttsFilePath != null) {
                File ttsFile = new File(ttsFilePath);
                if (ttsFile.exists() && ttsFile.length() > 0) {
                    File savedTts = new File(mContext.getFilesDir(), "tts_latest.mp3");
                    copyFile(ttsFile, savedTts);
                    playAudio(savedTts);
                    Log.d(TAG, "Playing TTS: " + savedTts.getAbsolutePath());
                    fileLog("PLAY tts=" + savedTts.getAbsolutePath() + " size=" + savedTts.length());
                } else {
                    Log.w(TAG, "TTS file empty or missing: " + ttsFilePath);
                    fileLog("WARN tts file empty: " + ttsFilePath);
                }
            }

            return newFixedLengthResponse(Response.Status.OK, "application/json",
                    "{\"result\":\"ok\"}");

        } catch (Exception e) {
            Log.e(TAG, "handleAiResult failed", e);
            fileLog("ERROR " + e.getMessage());
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                    "{\"result\":\"error\",\"message\":\"" + e.getMessage() + "\"}");
        }
    }

    private void playAudio(File audioFile) {
        try {
            if (mPlayer != null) {
                mPlayer.release();
            }
            mPlayer = new MediaPlayer();
            mPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mPlayer.setDataSource(audioFile.getAbsolutePath());
            mPlayer.setOnCompletionListener(mp -> {
                Log.d(TAG, "TTS playback complete");
                mp.release();
                mPlayer = null;
            });
            mPlayer.prepare();
            mPlayer.start();
        } catch (Exception e) {
            Log.e(TAG, "playAudio failed", e);
            fileLog("PLAY_ERROR " + e.getMessage());
        }
    }

    private void copyFile(File src, File dst) throws Exception {
        try (InputStream in = new java.io.FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        }
    }

    private void fileLog(String msg) {
        try {
            File logFile = new File(mContext.getFilesDir(), "tts_receiver.log");
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(new Date());
            java.io.FileWriter fw = new java.io.FileWriter(logFile, true);
            fw.write("[" + ts + "] " + msg + "\n");
            fw.close();
        } catch (Exception e) {
            Log.e(TAG, "fileLog failed", e);
        }
        try {
            File logFile = new File("/data/local/tmp/tts_receiver.log");
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(new Date());
            java.io.FileWriter fw = new java.io.FileWriter(logFile, true);
            fw.write("[" + ts + "] " + msg + "\n");
            fw.close();
        } catch (Exception ignored) {}
    }

    public void stopServer() {
        stop();
        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
        }
    }
}
