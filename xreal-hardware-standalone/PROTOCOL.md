# XREAL / Nreal Light USB Protocol Reference

> **CRITICAL**: Read this before modifying any USB communication code in this module.

## Device Identification
| Field | Value |
|-------|-------|
| Vendor ID | `0x0486` (1158) |
| Product ID | `0x573C` (22332) |
| Device Name | Nreal Light |
| USB Class | HID (Class 3) |
| IMU Interface | Interface 0 |
| IMU IN Endpoint | `0x81` (Interrupt IN, 64 bytes) |
| IMU OUT Endpoint | `0x01` (Interrupt OUT, 64 bytes) |

## IMU Activation Payload
```
0xAA, 0xC5, 0xD1, 0x21, 0x42, 0x04, 0x00, 0x19, 0x01
```

## CRITICAL: Transfer Method

> [!CAUTION]
> The magic payload **MUST** be sent via `bulkTransfer()` to the OUT endpoint.
> Using `controlTransfer()` will **silently fail** — no error, no data.

```kotlin
// CORRECT ✅
connection.bulkTransfer(endpointOut, magicPayload, magicPayload.size, 200)

// WRONG ❌ — silently fails
connection.controlTransfer(0x21, 0x09, 0x0300, 0, magicPayload, magicPayload.size, 1000)
```

## IMU Data Reading

> [!IMPORTANT]
> Use **synchronous** `bulkTransfer()` in a loop, NOT `UsbRequest` async polling.

```kotlin
// CORRECT ✅
while (active) {
    val bytesRead = connection.bulkTransfer(endpointIn, buffer, 64, 200)
    if (bytesRead > 0) processIMU(buffer)
}

// WRONG ❌ — doesn't work reliably
val request = UsbRequest()
request.initialize(connection, endpointIn)
request.queue(buffer)
```

## Android 14+ (targetSdk 34) Requirements

> [!WARNING]
> Android 14 requires explicit Intents with `FLAG_MUTABLE`. Implicit intents cause a silent crash.

```kotlin
// CORRECT ✅
val intent = Intent(ACTION_USB_PERMISSION).apply { setPackage(context.packageName) }
PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE)

// WRONG ❌ — crashes silently on Android 14+
PendingIntent.getBroadcast(context, 0, Intent(ACTION), PendingIntent.FLAG_IMMUTABLE)
PendingIntent.getBroadcast(context, 0, Intent(ACTION), PendingIntent.FLAG_MUTABLE) // implicit!
```

BroadcastReceiver registration also needs `RECEIVER_EXPORTED` on Android 13+:
```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
}
```

## Debugging Tips

- **Wireless ADB logcat is unreliable** for this app (logs often don't appear)
- Use `sLog()` callback pattern to display logs directly on the app screen
- Version number should be displayed on startup to confirm correct build is deployed
- Reference implementation: [enricoros/android-nreal](https://github.com/enricoros/android-nreal)

## Activation Sequence (Correct Order)
1. Find device by VID `0x0486`
2. Request USB permission (explicit Intent + FLAG_MUTABLE)
3. Open device, claim HID Interface 0
4. Send magic payload via `bulkTransfer` to OUT endpoint `0x01`
5. Start synchronous read loop on IN endpoint `0x81`
6. Validate packets: 64 bytes each, streaming at high frequency
