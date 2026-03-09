import re
import html
import sys

filename = r'C:\Users\User\Desktop\_Gemini - Google AI를 활용하는 방법.html'

try:
    with open(filename, 'r', encoding='utf-8', errors='ignore') as f:
        text = f.read()
except Exception as e:
    print(f"Error reading file: {e}")
    sys.exit(1)

print("Extracting code blocks...")
# Find all <pre> or <code> blocks that might contain Python or JSON
matches = re.findall(r'<pre[^>]*>(.*?)</pre>', text, re.DOTALL | re.IGNORECASE)
if not matches:
    matches = re.findall(r'<code[^>]*>(.*?)</code>', text, re.DOTALL | re.IGNORECASE)

found_relevant = False
for i, block in enumerate(matches):
    clean_text = html.unescape(re.sub(r'<[^>]+>', '', block))
    
    # Check if the block has relevance to checksum, timestamp, crc, format, etc.
    if 'def ' in clean_text or 'crc' in clean_text.lower() or 'checksum' in clean_text.lower() or 'struct' in clean_text.lower():
        print(f"\n--- RELEVANT CODE BLOCK {i} ---")
        print(clean_text)
        found_relevant = True

if not found_relevant:
    print("No relevant code blocks found with those keywords, printing all text blocks that look like code:")
    for i, block in enumerate(matches[:5]): # Print first 5
        clean_text = html.unescape(re.sub(r'<[^>]+>', '', block))
        print(f"\n--- CODE BLOCK {i} ---")
        print(clean_text[:500] + "...")
