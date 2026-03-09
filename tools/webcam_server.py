#!/usr/bin/env python3
"""
XREAL Remote Webcam Server
===========================
Streams webcam video (MJPEG) and microphone audio (PCM) over HTTP.
Designed for Tailscale LAN access from XREAL NativeAR app.

Usage:
    pip install opencv-python pyaudio
    python webcam_server.py                      # defaults: port 8554, cam 0
    python webcam_server.py --port 9000 --cam 1  # custom port + camera index
    python webcam_server.py --no-audio           # video only (no mic)

Endpoints:
    GET /video  -> MJPEG multipart stream (15fps, 640x480, quality 70)
    GET /audio  -> raw PCM stream (16kHz, 16-bit, mono)
    GET /status -> JSON server status
    GET /       -> simple HTML preview page
"""

import argparse
import io
import json
import threading
import time
from http.server import HTTPServer, BaseHTTPRequestHandler

import cv2

# Optional: audio support
try:
    import pyaudio
    HAS_PYAUDIO = True
except ImportError:
    HAS_PYAUDIO = False
    print("[WARN] PyAudio not installed. Audio streaming disabled.")
    print("       Install with: pip install pyaudio")


class StreamState:
    """Shared state for webcam + audio capture threads."""
    def __init__(self, cam_index=0, fps=15, width=640, height=480, jpeg_quality=70, enable_audio=True):
        self.cam_index = cam_index
        self.fps = fps
        self.width = width
        self.height = height
        self.jpeg_quality = jpeg_quality
        self.enable_audio = enable_audio and HAS_PYAUDIO

        # Video
        self.current_frame = None  # JPEG bytes
        self.frame_lock = threading.Lock()
        self.frame_event = threading.Event()
        self.video_running = False

        # Audio
        self.audio_buffer = bytearray()
        self.audio_lock = threading.Lock()
        self.audio_running = False

        # Stats
        self.actual_fps = 0.0
        self.frame_count = 0
        self.start_time = time.time()

    def start_video(self):
        """Start webcam capture thread."""
        self.video_running = True
        t = threading.Thread(target=self._video_loop, daemon=True)
        t.start()
        print(f"[VIDEO] Started: cam={self.cam_index}, {self.width}x{self.height}@{self.fps}fps, quality={self.jpeg_quality}")

    def start_audio(self):
        """Start microphone capture thread."""
        if not self.enable_audio:
            return
        self.audio_running = True
        t = threading.Thread(target=self._audio_loop, daemon=True)
        t.start()
        print("[AUDIO] Started: 16kHz, 16-bit, mono")

    def _video_loop(self):
        cap = cv2.VideoCapture(self.cam_index, cv2.CAP_DSHOW)
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, self.width)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, self.height)
        cap.set(cv2.CAP_PROP_FPS, self.fps)

        if not cap.isOpened():
            print(f"[ERROR] Cannot open camera {self.cam_index}")
            self.video_running = False
            return

        actual_w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
        actual_h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
        print(f"[VIDEO] Actual resolution: {actual_w}x{actual_h}")

        encode_params = [cv2.IMWRITE_JPEG_QUALITY, self.jpeg_quality]
        interval = 1.0 / self.fps
        fps_counter = 0
        fps_timer = time.time()

        while self.video_running:
            loop_start = time.time()
            ret, frame = cap.read()
            if not ret:
                time.sleep(0.01)
                continue

            _, jpeg = cv2.imencode('.jpg', frame, encode_params)
            jpeg_bytes = jpeg.tobytes()

            with self.frame_lock:
                self.current_frame = jpeg_bytes

            self.frame_event.set()
            self.frame_count += 1
            fps_counter += 1

            # Update FPS every second
            now = time.time()
            if now - fps_timer >= 1.0:
                self.actual_fps = fps_counter / (now - fps_timer)
                fps_counter = 0
                fps_timer = now

            # Throttle to target FPS
            elapsed = time.time() - loop_start
            sleep_time = interval - elapsed
            if sleep_time > 0:
                time.sleep(sleep_time)

        cap.release()
        print("[VIDEO] Stopped")

    def _audio_loop(self):
        try:
            pa = pyaudio.PyAudio()
            stream = pa.open(
                format=pyaudio.paInt16,
                channels=1,
                rate=16000,
                input=True,
                frames_per_buffer=1024
            )
            print("[AUDIO] Microphone opened successfully")

            while self.audio_running:
                data = stream.read(1024, exception_on_overflow=False)
                with self.audio_lock:
                    # Keep buffer under 64KB (2 seconds at 16kHz)
                    if len(self.audio_buffer) > 65536:
                        self.audio_buffer = self.audio_buffer[-32768:]
                    self.audio_buffer.extend(data)

            stream.stop_stream()
            stream.close()
            pa.terminate()
        except Exception as e:
            print(f"[AUDIO] Error: {e}")
            self.audio_running = False

    def stop(self):
        self.video_running = False
        self.audio_running = False


class StreamHandler(BaseHTTPRequestHandler):
    """HTTP handler for MJPEG video, PCM audio, and status endpoints."""

    state: StreamState = None  # Set by main()

    def log_message(self, format, *args):
        # Suppress per-request logging to reduce noise
        pass

    def do_GET(self):
        if self.path == '/video':
            self._handle_video()
        elif self.path == '/audio':
            self._handle_audio()
        elif self.path == '/status':
            self._handle_status()
        elif self.path == '/':
            self._handle_index()
        else:
            self.send_error(404)

    def _handle_video(self):
        """Stream MJPEG (multipart/x-mixed-replace)."""
        self.send_response(200)
        self.send_header('Content-Type', 'multipart/x-mixed-replace; boundary=frame')
        self.send_header('Cache-Control', 'no-cache')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()

        print(f"[VIDEO] Client connected: {self.client_address[0]}")
        try:
            while self.state.video_running:
                self.state.frame_event.wait(timeout=2.0)
                self.state.frame_event.clear()

                with self.state.frame_lock:
                    frame = self.state.current_frame

                if frame is None:
                    continue

                self.wfile.write(b'--frame\r\n')
                self.wfile.write(b'Content-Type: image/jpeg\r\n')
                self.wfile.write(f'Content-Length: {len(frame)}\r\n'.encode())
                self.wfile.write(b'\r\n')
                self.wfile.write(frame)
                self.wfile.write(b'\r\n')
                self.wfile.flush()

        except (BrokenPipeError, ConnectionResetError):
            pass
        print(f"[VIDEO] Client disconnected: {self.client_address[0]}")

    def _handle_audio(self):
        """Stream raw PCM audio (16kHz, 16-bit, mono)."""
        if not self.state.enable_audio or not self.state.audio_running:
            self.send_error(404, "Audio not available")
            return

        self.send_response(200)
        self.send_header('Content-Type', 'application/octet-stream')
        self.send_header('X-Audio-Rate', '16000')
        self.send_header('X-Audio-Channels', '1')
        self.send_header('X-Audio-Format', 'pcm_s16le')
        self.send_header('Cache-Control', 'no-cache')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()

        print(f"[AUDIO] Client connected: {self.client_address[0]}")
        try:
            while self.state.audio_running:
                chunk = None
                with self.state.audio_lock:
                    if len(self.state.audio_buffer) >= 2048:
                        chunk = bytes(self.state.audio_buffer[:4096])
                        self.state.audio_buffer = self.state.audio_buffer[4096:]

                if chunk:
                    self.wfile.write(chunk)
                    self.wfile.flush()
                else:
                    time.sleep(0.05)  # 50ms poll

        except (BrokenPipeError, ConnectionResetError):
            pass
        print(f"[AUDIO] Client disconnected: {self.client_address[0]}")

    def _handle_status(self):
        """Return JSON status."""
        status = {
            "streaming": self.state.video_running,
            "fps": round(self.state.actual_fps, 1),
            "resolution": f"{self.state.width}x{self.state.height}",
            "audio": self.state.audio_running,
            "frames": self.state.frame_count,
            "uptime_s": round(time.time() - self.state.start_time, 1)
        }
        body = json.dumps(status).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()
        self.wfile.write(body)

    def _handle_index(self):
        """Simple HTML preview page."""
        html = f"""<!DOCTYPE html>
<html><head><title>XREAL Webcam Server</title></head>
<body style="background:#111;color:#eee;font-family:monospace;text-align:center">
<h2>XREAL Remote Webcam</h2>
<img src="/video" style="max-width:90%;border:2px solid #0ff;border-radius:8px" />
<p>Status: <a href="/status" style="color:#0ff">/status</a></p>
</body></html>"""
        body = html.encode()
        self.send_response(200)
        self.send_header('Content-Type', 'text/html')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class ThreadedHTTPServer(HTTPServer):
    """Handle each request in a new thread (for concurrent video+audio clients)."""
    def process_request(self, request, client_address):
        t = threading.Thread(target=self.process_request_thread, args=(request, client_address))
        t.daemon = True
        t.start()

    def process_request_thread(self, request, client_address):
        try:
            self.finish_request(request, client_address)
        except Exception:
            self.handle_error(request, client_address)
        finally:
            self.shutdown_request(request)


def main():
    parser = argparse.ArgumentParser(description='XREAL Remote Webcam Server')
    parser.add_argument('--port', type=int, default=8554, help='HTTP port (default: 8554)')
    parser.add_argument('--cam', type=int, default=0, help='Camera index (default: 0)')
    parser.add_argument('--fps', type=int, default=15, help='Target FPS (default: 15)')
    parser.add_argument('--width', type=int, default=640, help='Frame width (default: 640)')
    parser.add_argument('--height', type=int, default=480, help='Frame height (default: 480)')
    parser.add_argument('--quality', type=int, default=70, help='JPEG quality 1-100 (default: 70)')
    parser.add_argument('--no-audio', action='store_true', help='Disable audio streaming')
    args = parser.parse_args()

    state = StreamState(
        cam_index=args.cam,
        fps=args.fps,
        width=args.width,
        height=args.height,
        jpeg_quality=args.quality,
        enable_audio=not args.no_audio
    )

    # Start capture threads
    state.start_video()
    state.start_audio()

    # Start HTTP server
    StreamHandler.state = state
    server = ThreadedHTTPServer(('0.0.0.0', args.port), StreamHandler)

    print(f"\n{'='*50}")
    print(f"  XREAL Remote Webcam Server")
    print(f"  Video:  http://0.0.0.0:{args.port}/video")
    print(f"  Audio:  http://0.0.0.0:{args.port}/audio {'(disabled)' if args.no_audio else ''}")
    print(f"  Status: http://0.0.0.0:{args.port}/status")
    print(f"  Preview: http://0.0.0.0:{args.port}/")
    print(f"{'='*50}\n")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[SERVER] Shutting down...")
        state.stop()
        server.shutdown()


if __name__ == '__main__':
    main()
