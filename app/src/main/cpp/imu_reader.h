#ifndef XREAL_IMU_READER_H
#define XREAL_IMU_READER_H

#include <cstdint>

namespace xreal {

/**
 * IMU data from XREAL Light
 * Streamed via HID endpoint on OV580
 */
struct IMUData {
  double timestamp; // Microseconds

  // Accelerometer (m/s^2)
  float accel_x;
  float accel_y;
  float accel_z;

  // Gyroscope (rad/s)
  float gyro_x;
  float gyro_y;
  float gyro_z;

  // Temperature (Celsius)
  float temperature;
};

/**
 * IMUReader - Access XREAL Light's IMU via HID
 *
 * The OV580 chip streams IMU data through a HID interrupt endpoint.
 * Protocol is simple and well-documented by community.
 */
class IMUReader {
public:
  IMUReader();
  ~IMUReader();

  /**
   * Initialize HID connection to IMU
   */
  bool initialize();

  /**
   * Read latest IMU sample
   * @param data Output IMU data
   * @return true if new data available
   */
  bool readSample(IMUData &data);

  /**
   * Get IMU sampling rate
   */
  int getSampleRate() const { return 200; } // 200 Hz typical

  void shutdown();

private:
  void *m_hid_device; // hidapi device handle
  bool m_initialized;

  // XREAL Light IMU HID endpoint
  static constexpr int VENDOR_ID = 0x0bda;
  static constexpr int PRODUCT_ID = 0x0580;
  static constexpr int IMU_INTERFACE = 3; // HID interface number

  bool parseIMUPacket(const uint8_t *data, int length, IMUData &out);
};

} // namespace xreal

#endif // XREAL_IMU_READER_H
