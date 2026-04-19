/*
 * wav2vec2 Korean postprocess implementation
 * Model: kresnik/wav2vec2-large-xlsr-korean (base-korean 3s variant)
 * Output: [1, 149, 56] jamo tokens
 * PAD/CTC blank = index 53
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <android/log.h>
#include "wav2vec_postprocess.h"
#include "../common/Utils.h"

// Korean model output dimensions
#define KO_VOCAB_SIZE 56
#define KO_SEQUENCE_LENGTH 149

// Special token IDs (from vocab.json)
#define KO_PAD_TOKEN_ID 53
#define KO_UNK_TOKEN_ID 52
#define KO_SPACE_TOKEN_ID 51
#define KO_BOS_TOKEN_ID 54  // <s>
#define KO_EOS_TOKEN_ID 55  // </s>

// Korean jamo token mapping (index -> UTF-8 string)
// From vocab.json: ㄱ=0, ㄲ=1, ㄴ=2, ㄷ=3, ... ㅣ=39, ㄳ=40, ... ㅄ=50, space=51
static const char* ko_get_token_text(int token_id) {
    switch (token_id) {
        case 0: return "\xe3\x84\xb1";   // ㄱ
        case 1: return "\xe3\x84\xb2";   // ㄲ
        case 2: return "\xe3\x84\xb4";   // ㄴ
        case 3: return "\xe3\x84\xb7";   // ㄷ
        case 4: return "\xe3\x84\xb8";   // ㄸ
        case 5: return "\xe3\x84\xb9";   // ㄹ
        case 6: return "\xe3\x85\x81";   // ㅁ
        case 7: return "\xe3\x85\x82";   // ㅂ
        case 8: return "\xe3\x85\x83";   // ㅃ
        case 9: return "\xe3\x85\x85";   // ㅅ
        case 10: return "\xe3\x85\x86";  // ㅆ
        case 11: return "\xe3\x85\x87";  // ㅇ
        case 12: return "\xe3\x85\x88";  // ㅈ
        case 13: return "\xe3\x85\x89";  // ㅉ
        case 14: return "\xe3\x85\x8a";  // ㅊ
        case 15: return "\xe3\x85\x8b";  // ㅋ
        case 16: return "\xe3\x85\x8c";  // ㅌ
        case 17: return "\xe3\x85\x8d";  // ㅍ
        case 18: return "\xe3\x85\x8e";  // ㅎ
        case 19: return "\xe3\x85\x8f";  // ㅏ
        case 20: return "\xe3\x85\x90";  // ㅐ
        case 21: return "\xe3\x85\x91";  // ㅑ
        case 22: return "\xe3\x85\x92";  // ㅒ
        case 23: return "\xe3\x85\x93";  // ㅓ
        case 24: return "\xe3\x85\x94";  // ㅔ
        case 25: return "\xe3\x85\x95";  // ㅕ
        case 26: return "\xe3\x85\x96";  // ㅖ
        case 27: return "\xe3\x85\x97";  // ㅗ
        case 28: return "\xe3\x85\x98";  // ㅘ
        case 29: return "\xe3\x85\x99";  // ㅙ
        case 30: return "\xe3\x85\x9a";  // ㅚ
        case 31: return "\xe3\x85\x9b";  // ㅛ
        case 32: return "\xe3\x85\x9c";  // ㅜ
        case 33: return "\xe3\x85\x9d";  // ㅝ
        case 34: return "\xe3\x85\x9e";  // ㅞ
        case 35: return "\xe3\x85\x9f";  // ㅟ
        case 36: return "\xe3\x85\xa0";  // ㅠ
        case 37: return "\xe3\x85\xa1";  // ㅡ
        case 38: return "\xe3\x85\xa2";  // ㅢ
        case 39: return "\xe3\x85\xa3";  // ㅣ
        case 40: return "\xe3\x84\xb3";  // ㄳ
        case 41: return "\xe3\x84\xb5";  // ㄵ
        case 42: return "\xe3\x84\xb6";  // ㄶ
        case 43: return "\xe3\x84\xba";  // ㄺ
        case 44: return "\xe3\x84\xbb";  // ㄻ
        case 45: return "\xe3\x84\xbc";  // ㄼ
        case 46: return "\xe3\x84\xbd";  // ㄽ
        case 47: return "\xe3\x84\xbe";  // ㄾ
        case 48: return "\xe3\x84\xbf";  // ㄿ
        case 49: return "\xe3\x85\x80";  // ㅀ
        case 50: return "\xe3\x85\x84";  // ㅄ
        case KO_SPACE_TOKEN_ID: return " ";
        case KO_UNK_TOKEN_ID: return "";
        case KO_PAD_TOKEN_ID: return "";
        case KO_BOS_TOKEN_ID: return "";
        case KO_EOS_TOKEN_ID: return "";
        default: return "";
    }
}

float wav2vec_ko_postprocess(float *tensor_data, char *result_text) {
    int sequence_length = KO_SEQUENCE_LENGTH;

    LOGD("=== wav2vec2 Korean Postprocess Start ===");
    LOGD("Sequence length: %d, Vocab size: %d, PAD=%d", sequence_length, KO_VOCAB_SIZE, KO_PAD_TOKEN_ID);

    // Greedy decoding: find max logit token at each time step
    // Memory layout: [seq, vocab] → tensor_data[seq * KO_VOCAB_SIZE + vocab]
    int predicted_tokens[KO_SEQUENCE_LENGTH];
    float total_confidence = 0.0f;

    for (int seq = 0; seq < sequence_length; seq++) {
        int max_index = 0;
        float max_logit = tensor_data[seq * KO_VOCAB_SIZE + 0];

        for (int vocab = 1; vocab < KO_VOCAB_SIZE; vocab++) {
            float logit = tensor_data[seq * KO_VOCAB_SIZE + vocab];
            if (logit > max_logit) {
                max_logit = logit;
                max_index = vocab;
            }
        }

        // Softmax probability of max token
        float sum_exp = 0.0f;
        for (int vocab = 0; vocab < KO_VOCAB_SIZE; vocab++) {
            float logit = tensor_data[seq * KO_VOCAB_SIZE + vocab];
            sum_exp += expf(logit - max_logit);
        }
        float max_prob = 1.0f / sum_exp;

        predicted_tokens[seq] = max_index;
        total_confidence += max_prob;

        if (seq < 10) {
            LOGD("Step %d: token=%d ('%s'), logit=%.3f, prob=%.3f",
                 seq, max_index, ko_get_token_text(max_index), max_logit, max_prob);
        }
    }

    // Count PAD tokens
    int pad_count = 0;
    for (int i = 0; i < sequence_length; i++) {
        if (predicted_tokens[i] == KO_PAD_TOKEN_ID) pad_count++;
    }
    LOGD("PAD token count: %d/%d", pad_count, sequence_length);

    float confidence = total_confidence / sequence_length;

    // CTC decoding: remove consecutive duplicates
    int ctc_tokens[KO_SEQUENCE_LENGTH];
    int ctc_len = 0;
    int prev_token = -1;
    for (int i = 0; i < sequence_length; i++) {
        if (predicted_tokens[i] != prev_token) {
            if (predicted_tokens[i] != KO_PAD_TOKEN_ID) {
                ctc_tokens[ctc_len++] = predicted_tokens[i];
            }
            prev_token = predicted_tokens[i];
        }
    }
    LOGD("After CTC decode: %d tokens (from %d)", ctc_len, sequence_length);

    // Convert to text (jamo)
    char temp_buffer[4096] = {0};
    for (int i = 0; i < ctc_len; i++) {
        const char* token_text = ko_get_token_text(ctc_tokens[i]);
        if (strlen(token_text) > 0) {
            strcat(temp_buffer, token_text);
        }
    }

    LOGD("Jamo text: '%s'", temp_buffer);

    // Copy result
    strncpy(result_text, temp_buffer, 1023);
    result_text[1023] = '\0';

    if (strlen(result_text) == 0) {
        strcpy(result_text, "[음성 감지 안됨]");
    }

    LOGD("=== wav2vec2 Korean Postprocess Complete ===");
    LOGD("Final result: '%s' (confidence: %.3f)", result_text, confidence);

    return confidence;
}
