-- The auth module now issues independently signed access and refresh JWTs.
-- Keep existing account IDs, password hashes and reservation ownership intact.
-- Existing opaque refresh tokens cannot migrate; users log in again after deployment.
UPDATE auth_session SET revoked_at = now() WHERE revoked_at IS NULL;
