import os
from google import genai

# Configure API key
api_key = "AIzaSyAQbpllbZGGrBNjgbt8T96ZqGCeEsvn3cU"
client = genai.Client(api_key=api_key)

print("Listing available models...")
try:
    # New API style for listing models
    for m in client.models.list():
        print(f"- {m.name} (Display Name: {m.display_name})")
except Exception as e:
    print(f"Error listing models: {e}")
