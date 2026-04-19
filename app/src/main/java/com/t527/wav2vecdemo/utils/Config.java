package com.t527.wav2vecdemo.utils;

public class Config {
    public static final String OBJECT_MODEL_FILE = "models/object/4.0.0_Beta.nb";
    public static final String OBJECT_LABEL_FILE = "models/object/cls_name.txt";

    public static final String PERSON_MODEL_FILE = "models/person/AwDetPersion_3.0.3_Beta.nb";
    public static final String PERSON_LABEL_FILE = "models/person/person_name.txt";

    // wav2vec 모델 설정 (T527 NPU에서 모든 wav2vec2 모델 실패: FP32/BF16=SRAM부족, INT16=NPU hang)
    public static final String WAV2VEC_MODEL_FILE = "models/wav2vec/wav2vec2_5s_int16.nb";
    public static final String WAV2VEC_VOCAB_FILE = "models/wav2vec/vocab.json";
    public static final String WAV2VEC_CONFIG_FILE = "models/wav2vec/tokenizer_config.json";

    // DeepSpeech2 모델 설정
    public static final String DEEPSPEECH2_MODEL_FILE = "models/deepspeech2/deepspeech2_uint8.nb";
    public static final String DEEPSPEECH2_VOCAB_FILE = "models/deepspeech2/vocab.txt";

    // Citrinet 모델 설정
    public static final String CITRINET_MODEL_FILE = "models/CitriNet/citrinet_3s_uint8.nb";

}
