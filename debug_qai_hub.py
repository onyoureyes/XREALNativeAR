
import qai_hub as hub

def debug_qai():
    print("🔍 -- QAI Hub Diagnostics --")
    
    # 1. Check Devices (Connectivity Test)
    print("\n1. Testing Device List (Auth Check)...")
    try:
        devices = hub.get_devices()
        print(f"✅ Auth OK. Found {len(devices)} available devices.")
        if len(devices) > 0:
            print(f" - Sample: {devices[0].name}")
    except Exception as e:
        print(f"❌ Device Listing Failed (Auth/Network Error): {e}")
        return

    # 2. Try Exact Model IDs
    candidates = [
        "whisper-tiny", 
        "whisper_tiny", 
        "openai_whisper_tiny",
        "openai/whisper-tiny"
    ]
    
    print("\n2. probing for Whisper Models...")
    for name in candidates:
        try:
            print(f"   Probing '{name}'...", end=" ")
            m = hub.get_model(name)
            print(f"match found! ID: {m.model_id}")
            return
        except Exception:
            print("not found.")

if __name__ == "__main__":
    debug_qai()
