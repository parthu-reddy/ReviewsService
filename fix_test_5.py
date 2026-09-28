import re

query_path = "src/test/java/com/fooddelivery/reviews/service/ReviewQueryServiceTest.java"
with open(query_path, 'r') as f:
    content = f.read()

content = content.replace('containsExactly("Priya R.", "Bombay Canteen", ReviewQueryService.DRIVER_DISPLAY_NAME,', 'containsExactly("Customer", "Bombay Canteen", ReviewQueryService.DRIVER_DISPLAY_NAME,')

with open(query_path, 'w') as f:
    f.write(content)
