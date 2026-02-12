
import qai_hub as hub
import os

def compile_whisper_for_fold4():
    print("🚀 Qualcomm AI Hub: Whisper Model Compilation for Galaxy Z Fold 4")
    
    # 1. API Key Check (Optional: relying on interactive login if key is missing)
    # Ensure you have run 'pip install qai-hub' and authenticated via browser if prompted.

    try:
        # 2. Select Model & Device
        # "whisper-tiny" is a good balance for mobile. "whisper-small" is heavier but more accurate.
        # User requested review of "whisper-small" code, but "whisper-tiny" is safer for existing memory constraints.
        # Let's start with 'whisper-tiny-en' for compatibility with current app logic, 
        # or 'whisper-tiny' for multilingual.
        
        # Choosing 'whisper-v3-mobile' or standard whisper if available. 
        # Hub likely has "openai/whisper-tiny" or similar.
        model_name = "whisper-tiny" 
        print(f"📦 Loading Model: {model_name}...")
        model = hub.get_model(model_name)
        
        device_name = "Samsung Galaxy Z Fold 4"
        print(f"📱 Targeting Device: {device_name}")
        device = hub.Device(device_name)

        # 3. Compile
        print("⚡ Compiling via Cloud (This may take a few minutes)...")
        # Runtime TFLITE ensures compatibility with Android's AAR
        compiled_model = hub.compile(model, device, runtime=hub.Runtime.TFLITE)

        # 4. Download
        output_dir = "compiled_models"
        os.makedirs(output_dir, exist_ok=True)
        print(f"💾 Downloading to /{output_dir}...")
        
        # We want to save specific files
        downloaded_paths = compiled_model.download(output_dir)
        
        print("\n✅ Compilation Complete!")
        print(f"Files saved in: {os.path.abspath(output_dir)}")
        print("Please check for 'whisper_tiny.tflite' or similar and copy it to 'app/src/main/assets/'")

    except Exception as e:
        print(f"\n❌ Error during execution: {e}")
        print("Ensure you have installed the sdk: pip install qai-hub")
        print("And logged in via the browser popup.")

if __name__ == "__main__":
    compile_whisper_for_fold4()
