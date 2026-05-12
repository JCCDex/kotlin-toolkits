# kotlin-toolkits

kotlin toolkits for jccdex

## Modules

- `:vault`: Encrypted key vault (DataStore + Protobuf + Tink). Main API: `VaultRepository`.
- `:did`: DID feature SDK (includes core storage/resolve APIs like `DidCoreService`). Main API: `DidSdk`.

## Test

```bash
./gradlew :vault:testDebugUnitTest
```
