package com.t527.wav2vecdemo.wav2vec;

import java.io.Serializable;

public class Wav2VecResult implements Serializable {
    private String transcription;
    private float confidence;

    public Wav2VecResult() {
    }

    public Wav2VecResult(String transcription, float confidence) {
        this.transcription = transcription;
        this.confidence = confidence;
    }

    public String getTranscription() {
        return transcription;
    }

    public void setTranscription(String transcription) {
        this.transcription = transcription;
    }

    public float getConfidence() {
        return confidence;
    }

    public void setConfidence(float confidence) {
        this.confidence = confidence;
    }

    @Override
    public String toString() {
        return "Wav2VecResult{" +
                "transcription='" + transcription + '\'' +
                ", confidence=" + confidence +
                '}';
    }
}