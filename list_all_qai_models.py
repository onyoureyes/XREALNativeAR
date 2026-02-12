
import qai_hub as hub

def list_all():
    print("[INFO] Listing ALL models to qai_hub_models.txt...")
    try:
        models = hub.get_models()
        with open("qai_hub_models.txt", "w", encoding="utf-8") as f:
            for m in models:
                try:
                    f.write(f"{m.model_id}\n")
                except Exception:
                    pass
        print(f"[SUCCESS] Saved {len(models)} models to file.")
    except Exception as e:
        print(f"[ERROR] {e}")

if __name__ == "__main__":
    list_all()
