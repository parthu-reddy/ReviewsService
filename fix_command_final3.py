test_path = "src/test/java/com/fooddelivery/reviews/service/ReviewCommandServiceTest.java"
with open(test_path, 'r') as f:
    content = f.read()

content = content.replace("USER_ID);", "USER_ID, com.fooddelivery.common.enums.RoleName.CUSTOMER);")
content = content.replace("USER_ID))", "USER_ID, com.fooddelivery.common.enums.RoleName.CUSTOMER))")

with open(test_path, 'w') as f:
    f.write(content)
