import sqlite3
import base64
import os

b64_path = r'c:\Users\User\.gemini\antigravity\scratch\XREALNativeAR\db_dump_b64.txt'
db_path = r'c:\Users\User\.gemini\antigravity\scratch\XREALNativeAR\temp_db.sqlite'

try:
    if not os.path.exists(b64_path):
        print(f"Error: Base64 file not found at {b64_path}")
        exit(1)

    with open(b64_path, 'rb') as f:
        b64_data = f.read()
    
    db_data = base64.b64decode(b64_data)
    
    with open(db_path, 'wb') as f:
        f.write(db_data)

    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()
    
    # 1. List Tables
    cursor.execute("SELECT name FROM sqlite_master WHERE type='table';")
    tables = cursor.fetchall()
    print("Tables found:", tables)

    # 2. Query Memory Nodes
    target_table = None
    for t in tables:
        if 'memory' in t[0]:
            target_table = t[0]
            break
    
    if target_table:
        print(f"\nQuerying last 20 rows from {target_table}...")
        try:
            # Try to get readable timestamp and location data
            cursor.execute(f"SELECT id, datetime(timestamp/1000, 'unixepoch', 'localtime'), role, content, latitude, longitude, metadata FROM {target_table} ORDER BY timestamp DESC LIMIT 20")
        except:
             # Fallback if datetime fails or columns differ
            cursor.execute(f"SELECT * FROM {target_table} ORDER BY timestamp DESC LIMIT 20")
            
        rows = cursor.fetchall()
        print(f"{'ID':<6} | {'Time':<20} | {'Role':<10} | {'Lat':<10} | {'Lon':<10} | {'Content'}")
        print("-" * 100)
        for row in rows:
            # row: id, time, role, content, lat, lon, meta
            lat = str(row[4]) if row[4] else "N/A"
            lon = str(row[5]) if row[5] else "N/A"
            meta_raw = str(row[6]) if row[6] else "N/A"
            meta = meta_raw[:50].replace('\n', ' ')
            content_raw = str(row[3]) if row[3] else ""
            content = content_raw[:30].replace('\n', ' ')
            print(f"{row[0]:<6} | {row[1]:<20} | {row[2]:<10} | {lat:<10} | {lon:<10} | {meta:<20} | {content}...")
    else:
        print("No 'memory' table found.")

    conn.close()

except Exception as e:
    print(f"Error: {e}")
