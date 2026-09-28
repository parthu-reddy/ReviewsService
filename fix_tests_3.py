import re

query_path = "src/test/java/com/fooddelivery/reviews/service/ReviewQueryServiceTest.java"
with open(query_path, 'r') as f:
    content = f.read()

# Replace USER_ID with CUSTOMER_ID.toString()
content = content.replace('USER_ID', 'CUSTOMER_ID.toString()')

# Add import for lenient
if 'import static org.mockito.Mockito.lenient;' not in content:
    content = content.replace('import static org.mockito.Mockito.when;', 'import static org.mockito.Mockito.when;\nimport static org.mockito.Mockito.lenient;')

with open(query_path, 'w') as f:
    f.write(content)
