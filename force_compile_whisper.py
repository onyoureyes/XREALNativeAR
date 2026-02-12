
import torch
import qai_hub as hub
import whisper
import os
import numpy as np

def force_compile():
    print("🚀 Qualcomm AI Hub: Force Compile Strategy")
    device_name = "Samsung Galaxy Z Fold 4"
    device = hub.Device(device_name)
    print(f"📱 Target Device: {device_name}")

    # STRATEGY 1: Direct ID Fetch (Bypassing List)
    print("\n[Strategy 1] Attempting Direct ID Fetch...")
    try:
        # Common variants to try
        target_id = "openai/whisper-tiny" # Most likely correct ID for QAI Hub
        print(f"   Requesting model ID: {target_id}")
        model = hub.get_model(target_id)
        print("   ✅ Success! Found model in Hub.")
        
        print("   ⚡ Submitting Compile Job...")
        job = hub.submit_compile_job(
            model=model,
            device=device,
            options="--target_runtime tflite"
        )
        print(f"   ⏳ Job {job.job_id} submitted. Waiting...")
        job.wait()
        model = job.get_target_model()
        model.download("compiled_models_hub")
        print("   🎉 Downloaded optimized model to 'compiled_models_hub/'")
        return
    except Exception as e:
        print(f"   ❌ Strategy 1 Failed: {e}")

    # STRATEGY 2: Local Trace & Submit (The "Direct Compile" Approach)
    print("\n[Strategy 2] Local Trace & Submit (Encoder Only)")
    print("   This compiles the heavy Encode part for NPU.")
    try:
        print("   📦 Loading local OpenAI Whisper 'tiny'...")
        py_model = whisper.load_model("tiny")
        py_model.cpu()
        py_model.eval()
        
        # Whisper Tiny Encoder Input: [1, 80, 3000] (Batch, Mels, Time)
        print("   🔍 Tracing Encoder...")
        dummy_input = torch.randn(1, 80, 3000)
        
        # Create a wrapper for tracing
        class EncoderWrapper(torch.nn.Module):
            def __init__(self, original_model):
                super().__init__()
                self.encoder = original_model.encoder
            def forward(self, x):
                return self.encoder(x)
                
        traced_encoder = torch.jit.trace(EncoderWrapper(py_model), dummy_input)
        print("   ✅ Trace Successful.")
        
        print("   ⚡ Submitting Traced Model to Hub...")
        job = hub.submit_compile_job(
            model=traced_encoder,
            device=device,
            input_specs={"x": ((1, 80, 3000), "float32")},
            options="--target_runtime tflite"
        )
        print(f"   ⏳ Job {job.job_id} submitted. Waiting...")
        job.wait()
        
        if job.get_status().state == "FAILED":
             print("   ❌ Compilation Job Failed on Cloud.")
             return

        target_model = job.get_target_model()
        out_dir = "compiled_models_trace"
        target_model.download(out_dir)
        print(f"   🎉 Downloaded optimized encoder to '{out_dir}/'")
        print("   ⚠️ Note: This is ONLY the encoder. You need to update WhisperEngine to use this component.")
        
    except Exception as e:
        print(f"   ❌ Strategy 2 Failed: {e}")
        print("\n❌ All strategies failed. Please proceed with Manual Download (Plan B).")

if __name__ == "__main__":
    force_compile()
