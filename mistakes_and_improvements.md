# Mistakes and Improvements — Reviews Service

Lessons from this service's own build. Synced into `CommonMistakesDocumentation/`.

## Phase 7: Resilience & Security (original build, 2026-08)

1. **Bucket4j Lettuce integration.** In Spring Boot 3 / Lettuce, `LettuceBasedProxyManager` needs
   the `RedisClient` obtained from `LettuceConnectionFactory.getNativeClient()` directly.

2. **Proxy/load-balancer rate limiting.** Relying on `request.getRemoteAddr()` behind a reverse
   proxy throttles every user as one. `X-Forwarded-For` was parsed instead.
   **Superseded 2026-09-11 — see M1 below.**

3. **GlobalExceptionHandler coverage.** `IllegalArgumentException` and `IllegalStateException` map
   to 400. Domain entities like `ReviewAggregate` throw them, and letting them reach the catch-all
   turned client input errors into 500s.

## Integration, 2026-09-11 (`RandomDocuments/ReviewsIntegration_2026-09-11`)

### M1 — `X-Forwarded-For` is set by the client
Item 2 above solved the wrong half of the problem. The gateway *appends* to `X-Forwarded-For` rather
than replacing it, so keying the bucket on its first element let any caller pick a fresh bucket per
request simply by sending a different value — the per-IP limit was decorative.

Behind the gateway the identity is already established and verified, so the bucket is now keyed on
`X-User-Id`, with the remote address kept only for the rare unauthenticated request. Reads and
writes get separate buckets: a restaurant page issues several aggregate reads while a customer
submits reviews for an order once.

### M2 — Three existence checks, none of which could succeed
`validateRestaurantExists` and `validateProductExists` called
`InternalRestaurantController`, which is `@PreAuthorize("hasAnyRole('SERVICE','RESTAURANT','ADMIN')")`.
`FeignSecurityInterceptor` forwards the **caller's** identity, and the caller of a review is a
customer. Every restaurant and product review would have 403'd at that call. `validateDriverExists`
had the same shape against identity-service.

They were also asking the wrong question. Existence is not eligibility: a customer who never ordered
could review anything that existed. One call to the order's owner —
`GET /api/v1/internal/orders/{orderId}/review-context` — answers "was this target on this customer's
delivered order", which is what the rule actually turns on. The three checks were deleted, not
wrapped.

### M3 — One review per user per entity, forever
`UNIQUE (entity_type, entity_id, user_id)`, plus an outbox idempotency key of
`review:<type>:<entity>:<user>` against a `UNIQUE` column. A customer's second order from the same
restaurant could never be reviewed — and the outbox collision would have failed the whole
transaction, not just the duplicate. Both moved to order scope.

### M4 — Completion is `DeliveryStatus`, not `OrderStatus`
`OrderStatus` has no `DELIVERED` value; its javadoc says so, and `HANDED_OVER` — its last
non-terminal value — means the rider has the food. Gating on it would let a customer rate a meal that
had not arrived. The customer order-history UI has a "Report Issue / Request Refund" button on
`HANDED_OVER` today, which has the same problem; noted, not fixed here.

### M5 — `metadata` was an unbounded client-supplied JSON blob
Accepted from the request, stored as JSONB, read by nothing. Removed from the API along with
`ValidJson`/`JsonValidator`. The **column stays**: `Deployment/SCHEMA_POLICY.md` makes migrations
forward-only, and Hibernate's `validate` does not object to an unmapped column. Dropping it is a
separate contract-release step.

## Improvements

- **Identity from `Principal`, not `@RequestHeader`.** `SecurityContextFilter` authenticates only
  after verifying the gateway's signature, so the principal is trustworthy where the raw header is
  merely present — and declaring the header put it in `openapi.json` as required, which broke the
  generated browser client.
- **Submission is atomic per order.** Reviews cannot be edited, so a partially accepted submission
  would be permanently unrepairable. One request, one eligibility resolution, one transaction.
- **Immutability is enforced in three places**, not asserted once: every column is
  `updatable = false`, there is no PUT/PATCH/DELETE anywhere in the module, and
  `uq_reviews_entity_order` rejects a second write. A resubmission answers 409 `ALREADY_REVIEWED`
  *with the existing review*, so the UI shows what was said rather than an error.
- **Reviews are ordered by a literal.** A `Sort` built from a request parameter is property-name
  injection, and the only index that serves the query is
  `(entity_type, entity_id, created_at DESC, id)`.
