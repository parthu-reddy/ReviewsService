import re

query_path = "src/test/java/com/fooddelivery/reviews/service/ReviewQueryServiceTest.java"
with open(query_path, 'r') as f:
    content = f.read()

# Fix everyParticipantOnTheOrderBecomesATarget entity types
content = content.replace('containsExactly(ReviewEntityType.RESTAURANT, ReviewEntityType.DRIVER,', 'containsExactly(ReviewEntityType.CUSTOMER, ReviewEntityType.RESTAURANT, ReviewEntityType.DRIVER,')

# Fix everyParticipantOnTheOrderBecomesATarget display names
content = content.replace('containsExactly("Bombay Canteen", ReviewQueryService.DRIVER_DISPLAY_NAME,', 'containsExactly("Priya R.", "Bombay Canteen", ReviewQueryService.DRIVER_DISPLAY_NAME,')

with open(query_path, 'w') as f:
    f.write(content)
