# kotlin-toolkits

kotlin toolkits for jccdex

## Modules

- `:vault`: Encrypted key vault (DataStore + Protobuf + Tink). Main API: `VaultRepository`.
- `:did`: DID feature SDK (core service, default Android Room storage, avatar credential assembly). Main API: `DidSdk`.

## DID

See `:did` detailed documentation:

- `kotlin-toolkits/did/README.zh-CN.md`

## Test

```bash
./gradlew :vault:testDebugUnitTest
./gradlew :did:testDebugUnitTest
```
