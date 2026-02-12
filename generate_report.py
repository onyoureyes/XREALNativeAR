import sqlite3
import base64
import os

db_path = r'c:\Users\User\.gemini\antigravity\scratch\XREALNativeAR\temp_db.sqlite'
report_path = r'c:\Users\User\.gemini\antigravity\brain\d69032c4-3bed-45fb-ab77-5b28d1939de7\database_report.md'

try:
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()
    
    cursor.execute("SELECT id, datetime(timestamp/1000, 'unixepoch', 'localtime'), role, content FROM memory_nodes ORDER BY timestamp DESC LIMIT 20")
    rows = cursor.fetchall()
    
    with open(report_path, 'w', encoding='utf-8') as f:
        f.write("# Database Report (Last 20 Entries)\n\n")
        f.write("| ID | Time | Role | Content |\n")
        f.write("|----|------|------|---------|\n")
        
        for row in rows:
            # Clean content for markdown table
            content = str(row[3]).replace('\n', ' ').replace('|', '\|')
            f.write(f"| {row[0]} | {row[1]} | {row[2]} | {content} |\n")
            
    print("Report generated.")
    conn.close()

except Exception as e:
    print(f"Error: {e}")
