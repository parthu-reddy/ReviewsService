test_path = "src/test/java/com/fooddelivery/reviews/service/ReviewCommandServiceTest.java"
with open(test_path, 'r') as f:
    content = f.read()

content = content.replace("doesNotContain(USER_ID, com.fooddelivery.common.enums.RoleName.CUSTOMER)", "doesNotContain(USER_ID)")
content = content.replace("UUID.fromString(USER_ID, com.fooddelivery.common.enums.RoleName.CUSTOMER)", "UUID.fromString(USER_ID)")

with open(test_path, 'w') as f:
    f.write(content)
