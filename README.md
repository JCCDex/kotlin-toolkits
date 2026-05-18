# kotlin-toolkits

kotlin toolkits for jccdex

## Modules

- `:vault`: Encrypted key vault (DataStore + Protobuf + Tink). Main API: `VaultRepository`.
- `:webview-bridge`: Shared hidden-WebView runtime and JS asset host. Main API: `WebviewBridgeEngine`.
- `:did`: DID feature SDK (core service, default Android Room storage, avatar credential assembly). Main API: `DidSdk`.
- `:nft`: NFT storage / avatar SDK. Main API: `NftSdk`.
- `:wallet`: Wallet bridge SDK (mnemonic/derivation/signing helpers over hidden WebView). Main API: `WalletSdk`.

## DID

See `:did` detailed documentation:

- `kotlin-toolkits/did/README.zh-CN.md`

## SDK Docs

- `kotlin-toolkits/vault/README.zh-CN.md`
- `kotlin-toolkits/webview-bridge/README.zh-CN.md`
- `kotlin-toolkits/nft/README.zh-CN.md`
- `kotlin-toolkits/wallet/README.zh-CN.md`

## Test

```bash
./gradlew :vault:testDebugUnitTest
./gradlew :webview-bridge:testDebugUnitTest
./gradlew :did:testDebugUnitTest
./gradlew :wallet:testDebugUnitTest
```
