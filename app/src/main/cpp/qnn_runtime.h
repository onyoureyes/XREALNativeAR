#ifndef QNN_RUNTIME_H
#define QNN_RUNTIME_H

#include <cstdint>
#include <string>
#include <vector>

/**
 * Wrapper for Qualcomm QNN (Neural Network) runtime
 * Loads and executes models on Hexagon NPU
 */
class QNNRuntime {
public:
  QNNRuntime();
  ~QNNRuntime();

  /**
   * Initialize QNN backend (Hexagon DSP)
   * @return true if successful
   */
  bool initialize();

  /**
   * Load DLC model file
   * @param modelPath Path to .dlc file
   * @return true if successful
   */
  bool loadModel(const char *modelPath);

  /**
   * Run inference on input tensor
   * @param inputData Raw RGB image data
   * @param inputSize Size in bytes
   * @param outputData Output tensor (pre-allocated)
   * @param outputSize Output size in bytes
   * @return true if successful
   */
  bool execute(const uint8_t *inputData, size_t inputSize, float *outputData,
               size_t outputSize);

  /**
   * Get model input dimensions
   */
  void getInputDims(int &width, int &height, int &channels);

  /**
   * Get model output dimensions
   */
  void getOutputDims(int &numDetections, int &numClasses);

private:
  void *m_qnn_interface_ptr;  // QNN interface provider (opaque)
  void *m_backend_lib_handle; // libQnnHtp.so handle
  void *m_qnnBackend;         // QNN backend handle
  void *m_device_handle;      // QNN device handle (Hexagon)
  void *m_qnnContext;         // QNN context handle
  void *m_qnnGraph;           // QNN graph handle

  int m_inputWidth;
  int m_inputHeight;
  int m_inputChannels;

  int m_outputDetections;
  int m_outputClasses;
};

#endif // QNN_RUNTIME_H
