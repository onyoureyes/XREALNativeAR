#!/usr/bin/env python3
"""
Generate Whisper TFLite inference assets: filters.bin and vocab.json.

Produces:
  - filters.bin : 80x201 mel filterbank, raw float32 little-endian (64,320 bytes)
  - vocab.json  : Whisper multilingual tokenizer vocabulary {token: id}

No external dependencies beyond Python stdlib + numpy.

Mel filterbank specs (matching OpenAI Whisper):
  - 80 mel filters
  - N_FFT = 400  ->  201 frequency bins (N_FFT//2 + 1)
  - Sample rate = 16000 Hz
  - Frequency range: 0 Hz to 8000 Hz (Nyquist)

Usage:
  python generate_whisper_assets.py [--output-dir DIR]
"""

import argparse
import json
import math
import os
import struct
import sys

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
SAMPLE_RATE = 16000
N_FFT = 400
N_FREQ_BINS = N_FFT // 2 + 1  # 201
N_MELS = 80
F_MIN = 0.0
F_MAX = SAMPLE_RATE / 2.0  # 8000.0


# ---------------------------------------------------------------------------
# Mel filterbank generation (no numpy required, pure Python)
# ---------------------------------------------------------------------------

def hz_to_mel(hz: float) -> float:
    """Convert frequency in Hz to mel scale (HTK formula)."""
    return 2595.0 * math.log10(1.0 + hz / 700.0)


def mel_to_hz(mel: float) -> float:
    """Convert mel scale back to Hz."""
    return 700.0 * (10.0 ** (mel / 2595.0) - 1.0)


def linspace(start: float, stop: float, num: int) -> list:
    """Linearly spaced values, matching numpy.linspace."""
    if num == 1:
        return [start]
    step = (stop - start) / (num - 1)
    return [start + i * step for i in range(num)]


def generate_mel_filterbank(
    sr: int = SAMPLE_RATE,
    n_fft: int = N_FFT,
    n_mels: int = N_MELS,
    f_min: float = F_MIN,
    f_max: float = F_MAX,
) -> list:
    """
    Generate mel filterbank matrix of shape [n_mels, n_fft//2+1].

    This follows the same algorithm as librosa.filters.mel with htk=True and
    norm=None (unnormalized triangular filters), which matches OpenAI Whisper's
    mel filterbank used in the original Python implementation.

    Returns a flat list of float32 values in row-major order.
    """
    n_freq = n_fft // 2 + 1

    # Mel scale edges: n_mels + 2 points (including lower and upper edges)
    mel_min = hz_to_mel(f_min)
    mel_max = hz_to_mel(f_max)
    mel_points = linspace(mel_min, mel_max, n_mels + 2)

    # Convert mel points back to Hz
    hz_points = [mel_to_hz(m) for m in mel_points]

    # Convert Hz to FFT bin indices (fractional)
    bin_points = [h * n_fft / sr for h in hz_points]

    # Build triangular filters
    filters = [0.0] * (n_mels * n_freq)

    for i in range(n_mels):
        left = bin_points[i]
        center = bin_points[i + 1]
        right = bin_points[i + 2]

        for j in range(n_freq):
            freq_bin = float(j)

            if left <= freq_bin < center and center != left:
                # Rising slope
                filters[i * n_freq + j] = (freq_bin - left) / (center - left)
            elif center <= freq_bin <= right and right != center:
                # Falling slope
                filters[i * n_freq + j] = (right - freq_bin) / (right - center)
            else:
                filters[i * n_freq + j] = 0.0

    return filters


def generate_mel_filterbank_slaney(
    sr: int = SAMPLE_RATE,
    n_fft: int = N_FFT,
    n_mels: int = N_MELS,
    f_min: float = F_MIN,
    f_max: float = F_MAX,
) -> list:
    """
    Generate mel filterbank using Slaney (auditory toolbox) normalization.

    This applies norm="slaney" which normalizes each filter by the width of
    its mel band (area normalization). OpenAI Whisper uses this normalization.

    Returns a flat list of float32 values in row-major order [n_mels, n_freq].
    """
    n_freq = n_fft // 2 + 1

    mel_min = hz_to_mel(f_min)
    mel_max = hz_to_mel(f_max)
    mel_points = linspace(mel_min, mel_max, n_mels + 2)

    hz_points = [mel_to_hz(m) for m in mel_points]
    bin_points = [h * n_fft / sr for h in hz_points]

    filters = [0.0] * (n_mels * n_freq)

    for i in range(n_mels):
        left = bin_points[i]
        center = bin_points[i + 1]
        right = bin_points[i + 2]

        for j in range(n_freq):
            freq_bin = float(j)

            if left <= freq_bin < center and center != left:
                filters[i * n_freq + j] = (freq_bin - left) / (center - left)
            elif center <= freq_bin <= right and right != center:
                filters[i * n_freq + j] = (right - freq_bin) / (right - center)
            else:
                filters[i * n_freq + j] = 0.0

        # Slaney normalization: 2 / (hz_right - hz_left)
        enorm = 2.0 / (hz_points[i + 2] - hz_points[i])
        for j in range(n_freq):
            filters[i * n_freq + j] *= enorm

    return filters


# ---------------------------------------------------------------------------
# Whisper multilingual vocabulary
# ---------------------------------------------------------------------------

def generate_whisper_vocab() -> dict:
    """
    Generate Whisper multilingual tokenizer vocabulary.

    The Whisper tokenizer is based on GPT-2's BPE with additional special tokens.
    The full vocabulary has 51865 tokens for multilingual models:
      - 0..50256: GPT-2 BPE tokens
      - 50257: <|endoftext|>
      - 50258: <|startoftranscript|>
      - 50259..50357: language tokens (<|en|>, <|zh|>, <|de|>, etc.)
      - 50358: <|translate|>
      - 50359: <|transcribe|>
      - 50360: <|startoflm|>
      - 50361: <|startofprev|>
      - 50362: <|nospeech|>
      - 50363: <|notimestamps|>
      - 50364..51864: timestamp tokens <|0.00|> .. <|30.00|> (1501 tokens at 0.02s steps)

    Since the full GPT-2 BPE vocabulary is 50257 tokens that require the actual
    merge rules to reconstruct, we generate a vocab.json with:
      1. Placeholder byte tokens for 0..255
      2. Special tokens with their correct IDs
      3. A note that for full BPE decoding, the original vocab must be used.

    For TFLite inference the vocab is primarily needed for special token IDs.
    """
    vocab = {}

    # GPT-2 byte-level BPE: tokens 0-255 are single bytes
    # Tokens 256-50256 are BPE merges (would need the actual merge table)
    for i in range(256):
        vocab[f"<|byte_{i:03d}|>"] = i

    # Mark BPE merge range as placeholder
    # In practice, the Android app may use a separate tokenizer or
    # the model's built-in token handling
    for i in range(256, 50257):
        vocab[f"<|bpe_{i}|>"] = i

    # Special tokens
    vocab["<|endoftext|>"] = 50257
    vocab["<|startoftranscript|>"] = 50258

    # Language tokens (99 languages in Whisper multilingual)
    languages = [
        "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr",
        "pl", "ca", "nl", "ar", "sv", "it", "id", "hi", "fi", "vi",
        "he", "uk", "el", "ms", "cs", "ro", "da", "hu", "ta", "no",
        "th", "ur", "hr", "bg", "lt", "la", "mi", "ml", "cy", "sk",
        "te", "fa", "lv", "bn", "sr", "az", "sl", "kn", "et", "mk",
        "br", "eu", "is", "hy", "ne", "mn", "bs", "kk", "sq", "sw",
        "gl", "mr", "pa", "si", "km", "sn", "yo", "so", "af", "oc",
        "ka", "be", "tg", "sd", "gu", "am", "yi", "lo", "uz", "fo",
        "ht", "ps", "tk", "nn", "mt", "sa", "lb", "my", "bo", "tl",
        "mg", "as", "tt", "haw", "ln", "ha", "ba", "jw", "su",
    ]
    for idx, lang in enumerate(languages):
        vocab[f"<|{lang}|>"] = 50259 + idx

    vocab["<|translate|>"] = 50358
    vocab["<|transcribe|>"] = 50359
    vocab["<|startoflm|>"] = 50360
    vocab["<|startofprev|>"] = 50361
    vocab["<|nospeech|>"] = 50362
    vocab["<|notimestamps|>"] = 50363

    # Timestamp tokens: <|0.00|> to <|30.00|> in 0.02s steps = 1501 tokens
    for i in range(1501):
        t = i * 0.02
        vocab[f"<|{t:.2f}|>"] = 50364 + i

    return vocab


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Generate Whisper TFLite assets (filters.bin, vocab.json)"
    )
    parser.add_argument(
        "--output-dir",
        default=".",
        help="Directory to write output files (default: current directory)",
    )
    parser.add_argument(
        "--norm",
        choices=["none", "slaney"],
        default="slaney",
        help="Mel filter normalization: 'slaney' (Whisper default) or 'none' (default: slaney)",
    )
    args = parser.parse_args()

    output_dir = args.output_dir
    os.makedirs(output_dir, exist_ok=True)

    # --- Generate mel filterbank ---
    print(f"Generating mel filterbank: {N_MELS} mels x {N_FREQ_BINS} freq bins")
    print(f"  N_FFT={N_FFT}, SR={SAMPLE_RATE}, f_min={F_MIN}, f_max={F_MAX}")
    print(f"  Normalization: {args.norm}")

    if args.norm == "slaney":
        filters = generate_mel_filterbank_slaney()
    else:
        filters = generate_mel_filterbank()

    # Verify shape
    assert len(filters) == N_MELS * N_FREQ_BINS, (
        f"Expected {N_MELS * N_FREQ_BINS} values, got {len(filters)}"
    )

    # Write as raw float32 little-endian
    filters_path = os.path.join(output_dir, "filters.bin")
    with open(filters_path, "wb") as f:
        for val in filters:
            f.write(struct.pack("<f", val))

    file_size = os.path.getsize(filters_path)
    expected_size = N_MELS * N_FREQ_BINS * 4  # 4 bytes per float32
    print(f"  Written: {filters_path} ({file_size} bytes)")
    assert file_size == expected_size, (
        f"Expected {expected_size} bytes, got {file_size}"
    )

    # Print some stats for verification
    non_zero = sum(1 for v in filters if v != 0.0)
    max_val = max(filters)
    min_nonzero = min(v for v in filters if v > 0.0)
    print(f"  Non-zero values: {non_zero}/{len(filters)}")
    print(f"  Value range: [{min_nonzero:.6f}, {max_val:.6f}] (excluding zeros)")

    # --- Generate vocab.json ---
    print("\nGenerating Whisper multilingual vocabulary...")
    vocab = generate_whisper_vocab()

    vocab_path = os.path.join(output_dir, "vocab.json")
    with open(vocab_path, "w", encoding="utf-8") as f:
        json.dump(vocab, f, ensure_ascii=False, indent=None, separators=(",", ":"))

    vocab_size = os.path.getsize(vocab_path)
    print(f"  Written: {vocab_path} ({vocab_size} bytes, {len(vocab)} tokens)")

    # Print key token IDs for verification
    print(f"  <|endoftext|> = {vocab['<|endoftext|>']}")
    print(f"  <|startoftranscript|> = {vocab['<|startoftranscript|>']}")
    print(f"  <|en|> = {vocab['<|en|>']}")
    print(f"  <|ko|> = {vocab['<|ko|>']}")
    print(f"  <|transcribe|> = {vocab['<|transcribe|>']}")
    print(f"  <|notimestamps|> = {vocab['<|notimestamps|>']}")
    print(f"  Last timestamp <|30.00|> = {vocab['<|30.00|>']}")

    # --- Instructions for TFLite model ---
    print("\n" + "=" * 70)
    print("WHISPER TFLITE MODEL DOWNLOAD")
    print("=" * 70)
    print("""
To obtain the Whisper TFLite model, choose one of these options:

Option 1: Use the existing models already in assets/
  The project already has whisper_encoder_base.tflite and
  whisper_decoder_base.tflite in app/src/main/assets/.

Option 2: Convert from HuggingFace using ai-edge-torch
  pip install ai-edge-torch transformers
  python -c "
  import ai_edge_torch
  from transformers import WhisperForConditionalGeneration
  model = WhisperForConditionalGeneration.from_pretrained('openai/whisper-tiny')
  # Export encoder and decoder separately for TFLite
  "

Option 3: Use pre-converted models from:
  https://github.com/usefulsensors/openai-whisper/releases
  https://github.com/vilassn/whisper_android/tree/main/app/src/main/assets

Place the model files alongside filters.bin in:
  app/src/main/assets/
""")

    print("=" * 70)
    print("FILES GENERATED SUCCESSFULLY")
    print("=" * 70)
    print(f"  {filters_path}  -> copy to app/src/main/assets/filters.bin")
    print(f"  {vocab_path}   -> copy to app/src/main/assets/vocab.json")
    print()

    return 0


if __name__ == "__main__":
    sys.exit(main())
