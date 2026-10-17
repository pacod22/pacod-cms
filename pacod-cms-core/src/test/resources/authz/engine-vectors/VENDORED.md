# Vendored: pacod-id rules-engine vectors

Copied verbatim from pacod-id, `policy/engine-vectors/` (Apache-2.0, as is this project). They are
the specification of the rules engine, which pacod-id ships as Rust, TypeScript and Python ports;
`io.pacod.cms.core.identity.authz` is a Java port, and `AuthzEngineVectorsTest` runs every check
here against it.

- Copied on 2026-10-16. Content digest (sha256 of the YAML files concatenated in name order,
  first 16 hex): `467dc60583f2b65b`.
- `README.md` is pacod-id's own description of the semantics, kept beside the files it describes.

## Upgrading

Copy the directory again from pacod-id, run `./mvnw test -pl pacod-cms-core -Dtest=AuthzEngineVectorsTest`,
and update the date and digest above. A new check that fails is a change in the specification:
the port follows it, never the other way round.
