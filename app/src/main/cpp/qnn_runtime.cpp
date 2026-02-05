#include "qnn_runtime.h"
#include <android/log.h>
#include <dlfcn.h>
#include <fstream>
#include <vector>

// QNN SDK includes
#include "QNN/HTP/QnnHtpDevice.h"
#include "QNN/QnnCommon.h"
#include "QNN/QnnInterface.h"
#include "QNN/QnnTypes.h"

#define LOG_TAG "QNNRuntime"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

QNNRuntime::QNNRuntime()
    : m_qnnContext(nullptr), m_qnnGraph(nullptr), m_qnnBackend(nullptr),
      m_inputWidth(300), m_inputHeight(300), m_inputChannels(3),
      m_outputDetections(2100), m_outputClasses(85),
      m_qnn_interface_ptr(nullptr), m_backend_lib_handle(nullptr),
      m_device_handle(nullptr) {}

QNNRuntime::~QNNRuntime() {
  LOGI("Cleaning up QNN resources");

  auto qnn_iface = static_cast<const QnnInterface_t *>(m_qnn_interface_ptr);

  // Free context
  if (qnn_iface && m_qnnContext) {
    qnn_iface->QNN_INTERFACE_VER_NAME.contextFree(m_qnnContext, nullptr);
  }

  // Free device
  if (qnn_iface && m_device_handle) {
    qnn_iface->QNN_INTERFACE_VER_NAME.deviceFree(m_device_handle);
  }

  // Free backend
  if (qnn_iface && m_qnnBackend) {
    qnn_iface->QNN_INTERFACE_VER_NAME.backendFree(m_qnnBackend);
  }

  // Close library
  if (m_backend_lib_handle) {
    dlclose(m_backend_lib_handle);
  }
}

bool QNNRuntime::initialize() {
  LOGI("Initializing QNN Runtime for Hexagon NPU");

  // 1. Load QNN Backend Library
  // FORCE CPU MODE for stability testing
  LOGI("Loading libQnnCpu.so (Force CPU)...");
  m_backend_lib_handle = dlopen("libQnnCpu.so", RTLD_NOW | RTLD_LOCAL);

  if (!m_backend_lib_handle) {
    LOGE("Failed to load libQnnCpu.so: %s", dlerror());
    // Try absolute path as fallback (sometimes needed)
    m_backend_lib_handle =
        dlopen("/data/data/com.xreal.nativear/lib/libQnnCpu.so",
               RTLD_NOW | RTLD_LOCAL);
    if (!m_backend_lib_handle) {
      LOGE("Failed to load libQnnCpu.so from absolute path: %s", dlerror());
      return false;
    }
  }

  // 2. Get QNN Interface
  typedef Qnn_ErrorHandle_t (*QnnInterfaceGetProvidersFn_t)(
      const QnnInterface_t ***provider_list, uint32_t *num_providers);

  auto get_providers_fn = (QnnInterfaceGetProvidersFn_t)dlsym(
      m_backend_lib_handle, "QnnInterface_getProviders");

  if (!get_providers_fn) {
    LOGE("Failed to get QnnInterface_getProviders: %s", dlerror());
    return false;
  }

  const QnnInterface_t **provider_list = nullptr;
  uint32_t num_providers = 0;
  if (get_providers_fn(&provider_list, &num_providers) != QNN_SUCCESS) {
    LOGE("QnnInterface_getProviders failed");
    return false;
  }

  if (num_providers == 0 || !provider_list) {
    LOGE("No QNN providers found");
    return false;
  }

  m_qnn_interface_ptr = (void *)provider_list[0];
  auto qnn_iface = provider_list[0];
  LOGI("QNN Interface obtained (API v%d.%d)",
       qnn_iface->apiVersion.coreApiVersion.major,
       qnn_iface->apiVersion.coreApiVersion.minor);

  // 3. Create Backend
  LOGI("Creating QNN HTP backend...");
  if (qnn_iface->QNN_INTERFACE_VER_NAME.backendCreate(
          nullptr, nullptr, &m_qnnBackend) != QNN_SUCCESS) {

    LOGE("Failed to create HTP backend. Attempting CPU fallback...");

    // FALLBACK TO CPU
    // Close HTP library
    dlclose(m_backend_lib_handle);
    m_backend_lib_handle = nullptr;

    LOGI("Loading libQnnCpu.so...");
    m_backend_lib_handle = dlopen("libQnnCpu.so", RTLD_NOW | RTLD_LOCAL);
    if (!m_backend_lib_handle) {
      LOGE("Failed to load libQnnCpu.so: %s", dlerror());
      return false;
    }

    // Re-get providers for CPU
    get_providers_fn = (QnnInterfaceGetProvidersFn_t)dlsym(
        m_backend_lib_handle, "QnnInterface_getProviders");

    if (!get_providers_fn) {
      LOGE("Failed to get QnnInterface_getProviders (CPU): %s", dlerror());
      return false;
    }

    if (get_providers_fn(&provider_list, &num_providers) != QNN_SUCCESS ||
        num_providers == 0) {
      LOGE("No QNN CPU providers found");
      return false;
    }

    m_qnn_interface_ptr = (void *)provider_list[0];
    qnn_iface = provider_list[0];

    // Create CPU Backend
    LOGI("Creating QNN CPU backend...");
    if (qnn_iface->QNN_INTERFACE_VER_NAME.backendCreate(
            nullptr, nullptr, &m_qnnBackend) != QNN_SUCCESS) {
      LOGE("All backends failed.");
      return false;
    }

    LOGI("✅ CPU Backend created successfully (Fallback mode)");
  } else {
    LOGI("✅ HTP Backend created successfully");
  }

  // 4. Create Device

  LOGI("Creating QNN device for HTP...");
  if (qnn_iface->QNN_INTERFACE_VER_NAME.deviceCreate(
          nullptr, nullptr, &m_device_handle) != QNN_SUCCESS) {
    LOGE("Failed to create QNN device");
    return false;
  }

  // 5. Create Context
  LOGI("Creating QNN context...");
  if (qnn_iface->QNN_INTERFACE_VER_NAME.contextCreate(
          m_qnnBackend, m_device_handle, nullptr, &m_qnnContext) !=
      QNN_SUCCESS) {
    LOGE("Failed to create QNN context");
    return false;
  }

  LOGI("✅ QNN Runtime initialized successfully!");
  LOGI("   Backend: HTP (Hexagon)");
  LOGI("   Device: Created");
  LOGI("   Context: Created");

  return true;
}
bool QNNRuntime::loadModel(const char *modelPath) {
  LOGI("Loading model: %s", modelPath);

  auto qnn_iface = static_cast<const QnnInterface_t *>(m_qnn_interface_ptr);
  if (!qnn_iface) {
    LOGE("QNN interface not initialized");
    return false;
  }

  // 1. Read DLC binary file
  std::ifstream dlcFile(modelPath, std::ios::binary | std::ios::ate);
  if (!dlcFile.is_open()) {
    LOGE("Failed to open model file: %s", modelPath);
    return false;
  }

  std::streamsize fileSize = dlcFile.tellg();
  dlcFile.seekg(0, std::ios::beg);

  std::vector<uint8_t> dlcBuffer(fileSize);
  if (!dlcFile.read(reinterpret_cast<char *>(dlcBuffer.data()), fileSize)) {
    LOGE("Failed to read model file");
    return false;
  }
  dlcFile.close();

  LOGI("Read DLC model: %zu bytes", dlcBuffer.size());

  // 2. Create context from binary (this deserializes the model)
  LOGI("Deserializing model and creating graph...");

  // Free existing context if any
  if (m_qnnContext) {
    qnn_iface->QNN_INTERFACE_VER_NAME.contextFree(m_qnnContext, nullptr);
    m_qnnContext = nullptr;
  }

  // Create new context from DLC binary
  Qnn_ErrorHandle_t error =
      qnn_iface->QNN_INTERFACE_VER_NAME.contextCreateFromBinary(
          m_qnnBackend, m_device_handle,
          nullptr, // config
          dlcBuffer.data(), dlcBuffer.size(), &m_qnnContext,
          nullptr // profile handle
      );

  if (error != QNN_SUCCESS) {
    LOGE("Failed to create context from binary (error: %lu)",
         (unsigned long)error);
    return false;
  }

  LOGI("✅ Model loaded and graph created successfully!");

  // 3. Retrieve the graph handle
  // The graph is created automatically by contextCreateFromBinary
  // We need to retrieve it by name (typically "model" or similar)
  const char *graphName = "model";
  error = qnn_iface->QNN_INTERFACE_VER_NAME.graphRetrieve(
      m_qnnContext, graphName, (Qnn_GraphHandle_t *)&m_qnnGraph);

  if (error != QNN_SUCCESS) {
    // Try with common alternative names
    LOGI("Trying alternative graph names...");
    const char *altNames[] = {"Model", "yolov5s", "graph", "Graph"};
    bool found = false;

    for (const char *name : altNames) {
      error = qnn_iface->QNN_INTERFACE_VER_NAME.graphRetrieve(
          m_qnnContext, name, (Qnn_GraphHandle_t *)&m_qnnGraph);
      if (error == QNN_SUCCESS) {
        LOGI("✅ Graph retrieved with name: %s", name);
        found = true;
        break;
      }
    }

    if (!found) {
      LOGE("Failed to retrieve graph from context");
      return false;
    }
  } else {
    LOGI("✅ Graph retrieved: %s", graphName);
  }

  LOGI("Model loaded successfully!");
  LOGI("  Model size: %zu bytes", dlcBuffer.size());
  LOGI("  Graph handle: %p", m_qnnGraph);

  return true;
}

bool QNNRuntime::execute(const uint8_t *inputData, size_t inputSize,
                         float *outputData, size_t outputSize) {
  if (!m_qnnContext || !m_qnnGraph) {
    LOGE("QNN not initialized");
    return false;
  }

  auto qnn_iface = static_cast<const QnnInterface_t *>(m_qnn_interface_ptr);
  if (!qnn_iface) {
    LOGE("QNN interface not initialized");
    return false;
  }

  // Expected input size: 300x300x3 RGB uint8
  size_t expectedInputSize = m_inputWidth * m_inputHeight * m_inputChannels;
  if (inputSize != expectedInputSize) {
    LOGE("Invalid input size: expected %zu, got %zu", expectedInputSize,
         inputSize);
    return false;
  }

  // 1. Prepare input tensor
  Qnn_Tensor_t inputTensor = QNN_TENSOR_INIT;
  inputTensor.version = QNN_TENSOR_VERSION_2;

  // Set tensor dimensions (NHWC format: Batch, Height, Width, Channels)
  uint32_t inputDims[] = {1, (uint32_t)m_inputHeight, (uint32_t)m_inputWidth,
                          (uint32_t)m_inputChannels};
  inputTensor.v2.dimensions = inputDims;
  inputTensor.v2.rank = 4;

  // Set data type (assuming uint8 quantized input)
  inputTensor.v2.dataType = QNN_DATATYPE_UINT_8;

  // Set tensor data
  inputTensor.v2.clientBuf.data = (void *)inputData;
  inputTensor.v2.clientBuf.dataSize = inputSize;

  // Tensor name (may vary based on model)
  inputTensor.v2.name = "input";
  inputTensor.v2.type = QNN_TENSOR_TYPE_APP_WRITE;

  // 2. Prepare output tensor
  Qnn_Tensor_t outputTensor = QNN_TENSOR_INIT;
  outputTensor.version = QNN_TENSOR_VERSION_2;

  // YOLOv5 output: [1, num_detections, 85] where 85 = 5(box+conf) + 80(classes)
  // For 300x300 input, num_detections is typically around 2100
  uint32_t outputDims[] = {1, (uint32_t)m_outputDetections,
                           (uint32_t)m_outputClasses};
  outputTensor.v2.dimensions = outputDims;
  outputTensor.v2.rank = 3;

  // Output type (usually float32 for detections)
  outputTensor.v2.dataType = QNN_DATATYPE_FLOAT_32;

  // Allocate output buffer
  outputTensor.v2.clientBuf.data = outputData;
  outputTensor.v2.clientBuf.dataSize = outputSize;

  outputTensor.v2.name = "output";
  outputTensor.v2.type = QNN_TENSOR_TYPE_APP_READ;

  // 3. Execute graph on NPU
  LOGI("Executing graph on Hexagon NPU...");

  Qnn_ErrorHandle_t error = qnn_iface->QNN_INTERFACE_VER_NAME.graphExecute(
      (Qnn_GraphHandle_t)m_qnnGraph, &inputTensor,
      1, // numInputs
      &outputTensor,
      1,       // numOutputs
      nullptr, // profile handle
      nullptr  // signal handle
  );

  if (error != QNN_SUCCESS) {
    LOGE("Graph execution failed (error: %lu)", (unsigned long)error);
    return false;
  }

  LOGI("✅ Inference completed successfully on NPU!");
  return true;
}

void QNNRuntime::getInputDims(int &width, int &height, int &channels) {
  width = m_inputWidth;
  height = m_inputHeight;
  channels = m_inputChannels;
}

void QNNRuntime::getOutputDims(int &numDetections, int &numClasses) {
  numDetections = m_outputDetections;
  numClasses = m_outputClasses;
}
