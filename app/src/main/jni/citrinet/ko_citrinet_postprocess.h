#pragma once
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// ko_citrinet output: awnn_get_output_buffers returns float32 (dequantized)
// memory layout: tensor_data[c * T + t]  NCHW (C=2049 outer, T=63 inner)
// blank_id = 2048
float ko_citrinet_postprocess(const float *tensor_data,
                              const char *vocab_file_path,
                              char *result_text);

#ifdef __cplusplus
}
#endif
