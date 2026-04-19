#ifndef __DEEPSPEECH2_POST_PROCESS_H__
#define __DEEPSPEECH2_POST_PROCESS_H__

#ifdef __cplusplus
extern "C" {
#endif

/**
 * DeepSpeech2 postprocess function (English model)
 * @param tensor_data NPU output tensor data
 * @param result_text result text buffer
 * @return confidence score
 */
float deepspeech2_postprocess(float *tensor_data, char *result_text);

#ifdef __cplusplus
}
#endif

#endif
