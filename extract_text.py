import re
import html
import sys

filename = r'C:\Users\User\Desktop\_Gemini - Google AI를 활용하는 방법.html'

try:
    with open(filename, 'r', encoding='utf-8', errors='replace') as f:
        text = f.read()
except Exception as e:
    print(f"Error reading file: {e}")
    sys.exit(1)

# Strip script and style elements
text = re.sub(r'<(script|style)[^>]*>.*?</\1>', '', text, flags=re.IGNORECASE | re.DOTALL)
# Strip all HTML tags
clean_text = html.unescape(re.sub(r'<[^>]+>', '\n', text))
# Remove multiple newlines
clean_text = re.sub(r'\n\s*\n', '\n', clean_text)

with open(r'd:\XREALNativeAR\XREALNativeAR\gemini_extracted.txt', 'w', encoding='utf-8') as f:
    f.write(clean_text)

print("Extraction complete.")
