import re
import os

test_path = "src/test/java/com/fooddelivery/reviews/service/ReviewCommandServiceTest.java"
with open(test_path, 'r') as f:
    content = f.read()

# Fix createReviews signature
content = re.sub(
    r'\.createReviews\(([^,]+),\s*([^)]+)\)',
    r'.createReviews(\1, \2, com.fooddelivery.common.enums.RoleName.CUSTOMER)',
    content
)

# Fix assertTargetOnOrder
content = re.sub(
    r'doThrow\(new ReviewNotAllowedException\([^)]*\)\)\.when\(eligibilityService\)\.assertTargetOnOrder\(any\(\),\s*any\(\),\s*any\(\)\);',
    """when(eligibilityService.authorizeTargets(any(), any(), any(), any())).thenAnswer(inv -> {
            java.util.List<com.fooddelivery.common.dto.order.OrderReviewTargetAuthorizationRequest> reqs = inv.getArgument(3);
            return reqs.stream().map(req -> com.fooddelivery.common.dto.order.OrderReviewAuthorizationResult.builder()
                    .targetType(req.getTargetType())
                    .targetId(req.getTargetId())
                    .allowed(false)
                    .reasonCode(com.fooddelivery.reviews.enums.ReviewRejectionReason.TARGET_NOT_ON_ORDER.name())
                    .build()).toList();
        });""",
    content
)

with open(test_path, 'w') as f:
    f.write(content)
