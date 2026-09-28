import re

test_path = "src/test/java/com/fooddelivery/reviews/service/ReviewCommandServiceTest.java"
with open(test_path, 'r') as f:
    content = f.read()

# Replace getEligibility signature
content = re.sub(
    r'\.createReviews\(([^,]+),\s*(CUSTOMER_ID\.toString\(\)|UUID\.randomUUID\(\)\.toString\(\))\)',
    r'.createReviews(\1, \2, com.fooddelivery.common.enums.RoleName.CUSTOMER)',
    content
)

with open(test_path, 'w') as f:
    f.write(content)
