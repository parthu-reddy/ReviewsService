import re

test_path = "src/test/java/com/fooddelivery/reviews/service/ReviewCommandServiceTest.java"
with open(test_path, 'r') as f:
    content = f.read()

# I will replace all CUSTOMER_ID.toString()) with CUSTOMER_ID.toString(), com.fooddelivery.common.enums.RoleName.CUSTOMER) IF preceded by createReviews in the same line
lines = content.split('\n')
for i, line in enumerate(lines):
    if 'createReviews' in line:
        line = line.replace('CUSTOMER_ID.toString())', 'CUSTOMER_ID.toString(), com.fooddelivery.common.enums.RoleName.CUSTOMER)')
        line = line.replace('UUID.randomUUID().toString())', 'UUID.randomUUID().toString(), com.fooddelivery.common.enums.RoleName.CUSTOMER)')
        lines[i] = line

with open(test_path, 'w') as f:
    f.write('\n'.join(lines))
