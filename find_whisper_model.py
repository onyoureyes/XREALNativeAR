
import qai_hub as hub

def find_whisper():
    print("🔍 Searching for Whisper models in Qualcomm AI Hub...")
    try:
        # List all models and filter for "whisper"
        all_models = hub.get_models()
        whisper_models = [m.model_id for m in all_models if "whisper" in m.model_id.lower()]
        
        if whisper_models:
            print(f"✅ Found {len(whisper_models)} Whisper model(s):")
            for m_id in whisper_models:
                print(f" - {m_id}")
        else:
            print("❌ No models found containing 'whisper'. Listing first 10 models to check naming convention:")
            for m in list(all_models)[:10]:
                print(f" - {m.model_id}")
                
    except Exception as e:
        print(f"❌ Error listing models: {e}")

if __name__ == "__main__":
    find_whisper()
