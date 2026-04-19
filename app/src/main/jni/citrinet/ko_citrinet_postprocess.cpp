#include "ko_citrinet_postprocess.h"
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#undef LOG_TAG
#include "../common/Utils.h"

#define KO_CITRINET_NUM_CLASSES   2049
#define KO_CITRINET_TIME_STEPS    38   // 300f model: 300 input → 38 output timesteps
#define KO_CITRINET_BLANK_ID      2048
#define KO_CITRINET_MAX_VOCAB     2049
#define KO_CITRINET_MAX_TOKEN_LEN 64
#define KO_CITRINET_RESULT_MAX    4096

// Output layout [1, 2049, 1, 38] NCHW:
// element at class c, time t = tensor_data[c * 38 + t]

float ko_citrinet_postprocess(const float *tensor_data,
                              const char *vocab_file_path,
                              char *result_text) {
    if (!tensor_data || !result_text) return 0.0f;
    result_text[0] = '\0';

    // 1. Argmax over class axis for each time step
    // NCHW layout [1, 2049, 1, 38]: element at class c, time t = tensor_data[c * 38 + t]
    // (confirmed by VNN generated code: size[0]=W=38, size[2]=C=2049, VSI_NN_DIM_FMT_NCHW)
    int decoded_ids[KO_CITRINET_TIME_STEPS];
    for (int t = 0; t < KO_CITRINET_TIME_STEPS; t++) {
        int best_class = 0;
        float best_val = tensor_data[0 * KO_CITRINET_TIME_STEPS + t];
        for (int c = 1; c < KO_CITRINET_NUM_CLASSES; c++) {
            float val = tensor_data[c * KO_CITRINET_TIME_STEPS + t];
            if (val > best_val) {
                best_val = val;
                best_class = c;
            }
        }
        decoded_ids[t] = best_class;
    }

    // 2. CTC collapse: remove consecutive repeats, then remove blank
    int ctc_ids[KO_CITRINET_TIME_STEPS];
    int ctc_len = 0;
    int prev = -1;
    for (int t = 0; t < KO_CITRINET_TIME_STEPS; t++) {
        int id = decoded_ids[t];
        if (id != prev) {
            if (id != KO_CITRINET_BLANK_ID) {
                ctc_ids[ctc_len++] = id;
            }
            prev = id;
        }
    }
    LOGD("KoCitrinet CTC decoded %d tokens", ctc_len);
    for (int i = 0; i < ctc_len; i++) {
        LOGD("KoCitrinet CTC token[%d] = %d", i, ctc_ids[i]);
    }

    // 3. Load vocab from file (line N -> token_id N)
    static char vocab[KO_CITRINET_MAX_VOCAB][KO_CITRINET_MAX_TOKEN_LEN];
    int vocab_size = 0;

    FILE *fp = fopen(vocab_file_path, "r");
    if (!fp) {
        LOGE("ko_citrinet_postprocess: cannot open vocab: %s", vocab_file_path);
        return 0.0f;
    }
    char line_buf[KO_CITRINET_MAX_TOKEN_LEN];
    while (vocab_size < KO_CITRINET_MAX_VOCAB &&
           fgets(line_buf, sizeof(line_buf), fp)) {
        int len = (int)strlen(line_buf);
        while (len > 0 && (line_buf[len-1] == '\n' || line_buf[len-1] == '\r')) {
            line_buf[--len] = '\0';
        }
        strncpy(vocab[vocab_size], line_buf, KO_CITRINET_MAX_TOKEN_LEN - 1);
        vocab[vocab_size][KO_CITRINET_MAX_TOKEN_LEN - 1] = '\0';
        vocab_size++;
    }
    fclose(fp);
    LOGD("KoCitrinet loaded %d vocab entries", vocab_size);

    // 4. Token ids -> Korean text
    // ▁ (U+2581, UTF-8: E2 96 81) = word boundary (SentencePiece)
    // ## prefix = BPE subword continuation (no space)
    int out_pos = 0;
    result_text[0] = '\0';

    for (int i = 0; i < ctc_len; i++) {
        int id = ctc_ids[i];
        if (id < 0 || id >= vocab_size) {
            LOGD("KoCitrinet token id %d out of range", id);
            continue;
        }
        const char *token = vocab[id];
        const char *text_part = token;
        int need_space = 0;

        // SentencePiece underline ▁ (3 bytes: E2 96 81)
        if ((unsigned char)token[0] == 0xE2 &&
            (unsigned char)token[1] == 0x96 &&
            (unsigned char)token[2] == 0x81) {
            if (out_pos > 0) need_space = 1;
            text_part = token + 3;
        }
        // BPE subword ##
        else if (token[0] == '#' && token[1] == '#') {
            text_part = token + 2;
        }

        if (need_space && out_pos < KO_CITRINET_RESULT_MAX - 1) {
            result_text[out_pos++] = ' ';
        }
        int tlen = (int)strlen(text_part);
        if (tlen > 0 && out_pos + tlen < KO_CITRINET_RESULT_MAX) {
            memcpy(result_text + out_pos, text_part, tlen);
            out_pos += tlen;
        }
    }
    result_text[out_pos] = '\0';

    LOGD("KoCitrinet result: '%s'", result_text);
    return 1.0f;
}
