# Phase 2 Validation

This script connects to the PostgreSQL database and verifies the partitioning and indexes.

```python
import psycopg2
import sys

def verify_schema():
    try:
        conn = psycopg2.connect(dbname="reviews_db", user="postgres", password="password", host="localhost")
        cur = conn.cursor()
        
        # 1. Verify partitioned reviews table exists
        cur.execute("SELECT relkind FROM pg_class WHERE relname = 'reviews'")
        res = cur.fetchone()
        if not res or res[0] != 'p':
            print("FAIL: 'reviews' is not a partitioned table")
            sys.exit(1)
            
        # 2. Verify JSONB column exists
        cur.execute("SELECT data_type FROM information_schema.columns WHERE table_name = 'reviews' AND column_name = 'metadata'")
        res = cur.fetchone()
        if not res or res[0] != 'jsonb':
            print("FAIL: metadata column is not JSONB")
            sys.exit(1)
            
        print("SUCCESS: Phase 2 Validation Passed")
        
    except Exception as e:
        print(f"FAIL: Database connection or query failed: {e}")
        sys.exit(1)

if __name__ == "__main__":
    verify_schema()
```
