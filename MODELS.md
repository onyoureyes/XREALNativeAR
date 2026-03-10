# Model Files — Download Guide

> git에는 포함되지 않음 (.gitignore). 각 컴퓨터에서 수동 다운로드 필요.

## app/src/main/assets/

| File | Size | Purpose | Source |
|------|------|---------|--------|
| `yolov8n_full_integer_quant.tflite` | 3.3MB | Object detection (YOLOv8n INT8) | [Ultralytics](https://github.com/ultralytics/ultralytics) → TFLite export |
| `centernet_pose.tflite` | 46MB | Pose estimation (192x192, 17 keypoints) | [TF Hub CenterNet](https://tfhub.dev/tensorflow/centernet/hourglass_512x512_kpts/1) |
| `face_detection_front.tflite` | 225KB | BlazeFace (128x128, 896 anchors) | [MediaPipe](https://developers.google.com/mediapipe/solutions/vision/face_detector) |
| `mobilefacenet.tflite` | 5MB | Face embedding (ArcFace) | [insightface/MobileFaceNet](https://github.com/deepinsight/insightface) |
| `palm_detection_lite.tflite` | 2.3MB | Hand detection | [MediaPipe Hands](https://developers.google.com/mediapipe/solutions/vision/hand_landmarker) |
| `hand_landmark_lite.tflite` | 5.3MB | Hand landmarks (21 pts) | MediaPipe Hands |
| `mobilenet_v3_feature_vector.tflite` | 17MB | Image embedding | [TF Hub MobileNetV3](https://tfhub.dev/google/imagenet/mobilenet_v3_large_100_224/feature_vector/5) |
| `fer_emotion.tflite` | 467KB | Emotion classification | FER2013 trained |
| `whisper_encoder_base.tflite` | 46MB | Whisper base encoder (fallback STT) | [OpenAI Whisper](https://github.com/openai/whisper) → TFLite |
| `whisper_decoder_base.tflite` | 144MB | Whisper base decoder (fallback STT) | OpenAI Whisper → TFLite |
| `yamnet.tflite` | 4MB | Audio event classification | [TF Hub YAMNet](https://tfhub.dev/google/yamnet/1) |
| `universal_sentence_encoder.tflite` | 5.9MB | Text embedding (USE) | [TF Hub USE](https://tfhub.dev/google/universal-sentence-encoder/4) |
| `convnext_base-convnext-base-float.tflite` | 338MB | ConvNeXt vision (optional) | Qualcomm AI Hub |
| `cheetah_korean.pv` | 58MB | Picovoice Cheetah Korean STT (deprecated) | [Picovoice](https://picovoice.ai/) |
| `detectron2_detection-*.tflite/.dlc` | 18-69MB | Detectron2 proposals (optional) | Qualcomm AI Hub |
| `resnet_2plus1d-*.dlc` | 30MB | Video action recognition (optional) | Qualcomm AI Hub |
| `face_attrib_net-*.tflite` | 11MB | Facial attribute detection (optional) | Qualcomm AI Hub |

## whisper-standalone/src/main/assets/

| File | Size | Purpose | Source |
|------|------|---------|--------|
| `huggingface_wavlm_base_plus.tflite` | 371MB | WavLM speaker embedding (deprecated, replaced by 3D-Speaker) | [HuggingFace WavLM](https://huggingface.co/microsoft/wavlm-base-plus) |
| `whisper_small-hfwhisperdecoder-qualcomm_qcs8450_proxy.bin` | 343MB | Whisper Small QNN decoder (optional) | Qualcomm AI Hub |
| `whisper_small-hfwhisperencoder-qualcomm_qcs8450_proxy.bin` | 199MB | Whisper Small QNN encoder (optional) | Qualcomm AI Hub |

## app/libs/

| File | Size | Purpose | Source |
|------|------|---------|--------|
| `snpe-release.aar` | 45MB | Qualcomm SNPE runtime (deprecated) | [Qualcomm AI Engine](https://developer.qualcomm.com/software/qualcomm-neural-processing-sdk) |
| `nr_common.aar` | 26MB | XREAL SDK common (deprecated) | XREAL Developer |
| `nr_loader.aar` | 1MB | XREAL SDK loader (deprecated) | XREAL Developer |

## On-Device Models (not in repo, deployed via adb push)

| File | Size | Location on Device | Source |
|------|------|--------------------|--------|
| `Qwen3-0.6B-Q8_0.gguf` | 639MB | `/sdcard/edge_models/` | [HuggingFace Qwen3](https://huggingface.co/Qwen/Qwen3-0.6B-GGUF) |
| `Qwen3-1.7B-Q4_K_M.gguf` | 1.2GB | `/sdcard/edge_models/` | [HuggingFace Qwen3](https://huggingface.co/Qwen/Qwen3-1.7B-GGUF) |
| `Qwen3-1.7B-Q8_0.gguf` | 1.9GB | `/sdcard/edge_models/` | HuggingFace Qwen3 |
| `model.int8.onnx` + `tokens.txt` | 226MB | `/sdcard/Android/data/com.xreal.nativear/files/models/sherpa-onnx-sense-voice/` | [sherpa-onnx SenseVoice](https://github.com/k2-fsa/sherpa-onnx/releases) |
| 3D-Speaker ECAPA-TDNN | 20MB | `/sdcard/Android/data/com.xreal.nativear/files/models/3dspeaker-ecapa-tdnn/` | [3D-Speaker](https://github.com/modelscope/3D-Speaker) |

## Essential vs Optional

**필수 (앱 실행에 필요):**
- yolov8n, centernet_pose, face_detection_front, mobilefacenet
- palm_detection_lite, hand_landmark_lite
- mobilenet_v3, fer_emotion, yamnet, universal_sentence_encoder
- whisper_encoder_base, whisper_decoder_base (fallback STT)

**선택 (deprecated 또는 실험용):**
- convnext_base, detectron2, resnet_2plus1d, face_attrib_net
- cheetah_korean.pv (Picovoice 교체됨)
- huggingface_wavlm (3D-Speaker로 교체됨)
- whisper_small QNN proxy (SenseVoice로 교체됨)
- snpe-release.aar, nr_common.aar, nr_loader.aar (deprecated)
