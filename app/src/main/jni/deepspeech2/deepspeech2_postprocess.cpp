/*
 * DeepSpeech2 postprocess implementation (English model)
 */
#include "deepspeech2_postprocess.h"
#include "../common/Utils.h"
#include <android/log.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>


// DeepSpeech2 English model output size
#define VOCAB_SIZE 29
#define SEQUENCE_LENGTH 378 // Model output: [1, 378, 29] (downsampled from 756)

// DeepSpeech2 alphabet: [' ', 'a', 'b', 'c', ..., 'z', "'", BLANK]
// Index 0-27: characters, Index 28: BLANK token
static const char *ALPHABET[] = {
    " ", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n",
    "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z", "'", ""};

// English token mapping (DeepSpeech2)
static const char *get_token_text(int token_id) {
  if (token_id >= 0 && token_id < 29) {
    return ALPHABET[token_id];
  }
  return "";
}

// Remove consecutive duplicate tokens (CTC decoding)
void removeDuplicateTokens(int *tokens, int *length) {
  if (*length <= 1) {
    return;
  }

  int *result = (int *)malloc(*length * sizeof(int));
  int resultIndex = 0;
  result[resultIndex++] = tokens[0];

  for (int i = 1; i < *length; i++) {
    if (tokens[i] != tokens[i - 1]) {
      result[resultIndex++] = tokens[i];
    }
  }

  memcpy(tokens, result, resultIndex * sizeof(int));
  *length = resultIndex;
  free(result);
}

float deepspeech2_postprocess(float *tensor_data, char *result_text) {
  // Model output size: [sequence_length, vocab_size] in memory
  int sequence_length = SEQUENCE_LENGTH;

  LOGD("=== DeepSpeech2 Postprocess Start ===");
  LOGD("Sequence length: %d, Vocab size: %d", sequence_length, VOCAB_SIZE);

  // Greedy decoding: find max probability token at each time step
  // NPU outputs in [vocab, time] format: tensor_data[vocab * time + t]
  int predicted_tokens[SEQUENCE_LENGTH];
  float total_confidence = 0.0f;

  LOGD("=== CTC Decoding ===");

  for (int t = 0; t < sequence_length; t++) {
    int max_index = 0;
    // Assume output layout is [Time, Vocab] (Interleaved)
    // tensor_data[t * VOCAB_SIZE + v]
    float max_logit = tensor_data[t * VOCAB_SIZE + 0];

    // Find max logit across all vocab for this time position
    for (int v = 1; v < VOCAB_SIZE; v++) {
      float logit = tensor_data[t * VOCAB_SIZE + v];
      if (logit > max_logit) {
        max_logit = logit;
        max_index = v;
      }
    }

    // Calculate softmax sum for numerical stability
    float sum_exp = 0.0f;
    for (int v = 0; v < VOCAB_SIZE; v++) {
      float logit = tensor_data[t * VOCAB_SIZE + v];
      sum_exp += expf(logit - max_logit);
    }

    // Calculate probability of max token
    float max_prob = 1.0f / sum_exp;

    predicted_tokens[t] = max_index;
    total_confidence += max_prob;

    // Debug output for first 10 steps
    if (t < 10) {
      LOGD("Step %d: token=%d ('%s'), logit=%.3f, prob=%.3f", t, max_index,
           get_token_text(max_index), max_logit, max_prob);
    }
  }

  LOGD("=== Predicted Tokens (first 30) ===");
  for (int i = 0; i < 30 && i < sequence_length; i++) {
    const char *token_str = get_token_text(predicted_tokens[i]);
    if (strlen(token_str) > 0) {
      LOGD("Token[%d] = %d ('%s')", i, predicted_tokens[i], token_str);
    } else {
      LOGD("Token[%d] = %d (BLANK)", i, predicted_tokens[i]);
    }
  }

  // Count BLANK tokens (index 28)
  int blank_count = 0;
  for (int i = 0; i < sequence_length; i++) {
    if (predicted_tokens[i] == 28)
      blank_count++;
  }
  LOGD("BLANK token count: %d/%d", blank_count, sequence_length);

  // Calculate average confidence
  float confidence = total_confidence / sequence_length;
  LOGD("Average confidence: %.3f", confidence);

  // CTC decoding: remove consecutive duplicate tokens
  LOGD("Before duplicate removal: %d tokens", sequence_length);
  removeDuplicateTokens(predicted_tokens, &sequence_length);
  LOGD("After duplicate removal: %d tokens", sequence_length);

  // Convert tokens to text
  result_text[0] = '\0';
  char temp_buffer[2048] = {0};

  LOGD("=== Token to Text Conversion ===");
  int valid_tokens = 0;

  for (int i = 0; i < sequence_length; i++) {
    const char *token_text = get_token_text(predicted_tokens[i]);
    if (strlen(token_text) > 0) {
      strcat(temp_buffer, token_text);
      valid_tokens++;
      if (valid_tokens <= 20) {
        LOGD("Token %d ('%s') -> '%s'", predicted_tokens[i],
             get_token_text(predicted_tokens[i]), token_text);
      }
    }
  }

  LOGD("Valid tokens: %d, Text length: %d", valid_tokens,
       (int)strlen(temp_buffer));

  // Copy result (prevent buffer overflow)
  strncpy(result_text, temp_buffer, 1023);
  result_text[1023] = '\0';

  // If empty result, set placeholder
  if (strlen(result_text) == 0) {
    strcpy(result_text, "[NO SPEECH DETECTED]");
    LOGD("Empty result, using placeholder");
  }

  LOGD("=== DeepSpeech2 Postprocess Complete ===");
  LOGD("Final result: '%s' (confidence: %.3f)", result_text, confidence);

  return confidence;
}
