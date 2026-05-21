# kotlin-toolkits

kotlin toolkits for jccdex

## Modules

- `:core`: Shared domain models (`ChainType`, `Path`, `WalletAccount`) for wallet / account / did / nft.
- `:account`: Wallet account metadata storage (`ccdao_accounts.db`). Main API: `AccountSdk`.
- `:vault`: Encrypted key vault (DataStore + Protobuf + Tink). Main API: `VaultRepository`.
- `:webview-bridge`: Shared hidden-WebView runtime and JS asset host. Main API: `WebviewBridgeEngine`.
- `:did`: DID SDK (document CRUD, NFT credential issue/verify/bind, authorized-avatar VCID binding, Room storage). Main API: `DidSdk`.
- `:nft`: NFT storage / avatar SDK. Main API: `NftSdk`.
- `:wallet`: Wallet bridge SDK (mnemonic/derivation/signing helpers over hidden WebView). Main API: `WalletSdk`.

## DID

See `:did` documentation:

- `kotlin-toolkits/did/README.zh-CN.md`

## SDK Docs

- `kotlin-toolkits/did/README.zh-CN.md`
- `kotlin-toolkits/account/README.zh-CN.md`
- `kotlin-toolkits/vault/README.zh-CN.md`
- `kotlin-toolkits/webview-bridge/README.zh-CN.md`
- `kotlin-toolkits/nft/README.zh-CN.md`
- `kotlin-toolkits/wallet/README.zh-CN.md`

## Test

```bash
./gradlew :core:testDebugUnitTest
./gradlew :account:testDebugUnitTest
./gradlew :vault:testDebugUnitTest
./gradlew :webview-bridge:testDebugUnitTest
./gradlew :did:testDebugUnitTest
./gradlew :wallet:testDebugUnitTest
```
