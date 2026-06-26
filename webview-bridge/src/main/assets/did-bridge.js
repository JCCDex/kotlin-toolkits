// PromiseBridge for DID-only hidden WebView runtime.
// Kotlin calls PromiseBridge.call(method, params, id).
// JS must callback window.JSBridge.onPromiseResult(id, JSON.stringify({ result }))
// or window.JSBridge.onPromiseResult(id, JSON.stringify({ error: "..." })).

(function (global) {
  const jccWallet = window.jcc_wallet;
  const jtWallet = jccWallet.jtWallet;
  const ethWallet = jccWallet.ethWallet;
  const {
    IpfsClient,
    EthrDidPublish,
    SwtcDidPublish,
    SwtcDidResolver,
    EthrDidResolver,
    EthrDid,
    SwtcDid,
    getKeyDoc,
    Secp256k1DidKeypair,
    EthrDidDocument,
    SwtcDidDocument,
    BaseNftVC,
    issueCredential,
    ETHER_NFTOWNERSHIP_CONTEXT,
    SWTC_NFTOWNERSHIP_CONTEXT,
    ETHER_NFT_USAGE_AUTHORIZATION_CONTEXT,
    SWTC_NFT_USAGE_AUTHORIZATION_CONTEXT
  } = window.jcc_did;

  function bytesToHex(bytes) {
    let out = "";
    for (let i = 0; i < bytes.length; i++) {
      out += bytes[i].toString(16).padStart(2, "0");
    }
    return out;
  }

  // Bridge param 中的二进制（来自 JSON 序列化）可能是普通数组或 {0:..,1:..} 对象。
  function toUint8Array(data) {
    if (data instanceof Uint8Array) return data;
    if (Array.isArray(data)) return Uint8Array.from(data);
    if (data && typeof data === "object") return Uint8Array.from(Object.values(data));
    throw new Error("Invalid binary data");
  }

  function normalizePrivateKey(privateKey) {
    if (!privateKey) {
      throw new Error("Private key is required");
    }
    return privateKey.length === 66 ? privateKey.substring(2) : privateKey;
  }

  const client = new IpfsClient({
    baseURL: "https://wodecards.wh.jccdex.cn:8550"
  });

  const swtcResolver = new SwtcDidResolver(client);
  const ethrResolver = new EthrDidResolver(client);
  const swtcPublisher = new SwtcDidPublish(client);
  const ethrPublisher = new EthrDidPublish(client);

  function resolveDidRuntime(did) {
    if (!did) {
      throw new Error("DID is required");
    }
    if (swtcResolver.supports(did)) {
      return {
        didObject: SwtcDid.fromIdentifier(did.substring(did.lastIndexOf(":") + 1)),
        resolver: swtcResolver,
        publisher: swtcPublisher,
        nftContext: SWTC_NFTOWNERSHIP_CONTEXT
      };
    }
    if (ethrResolver.supports(did)) {
      return {
        didObject: EthrDid.fromIdentifier(did.substring(did.lastIndexOf(":") + 1)),
        resolver: ethrResolver,
        publisher: ethrPublisher,
        nftContext: ETHER_NFTOWNERSHIP_CONTEXT
      };
    }
    throw new Error("Unsupported DID method");
  }

  const methods = {
    async publishDid(params) {
      let { didDocument, privateKey, did } = params;

      if (!privateKey) {
        throw new Error("Private key is required for DID publishing");
      }
      if (!didDocument) {
        throw new Error("DID document is required for publishing");
      }

      const runtime = resolveDidRuntime(did);
      if (typeof didDocument === "string") {
        didDocument = JSON.parse(didDocument);
      }
      if (privateKey.length === 66) {
        privateKey = privateKey.substring(2);
      }

      await runtime.publisher.upload(did, didDocument, privateKey);
      return { code: "0", message: "success" };
    },

    async didResolve(params) {
      let { did } = params;
      did = did.split("#")[0]; // Remove fragment if present
      const runtime = resolveDidRuntime(did);
      try {
        return await runtime.resolver.resolve(did);
      } catch (e) {
        if (runtime.resolver.noLink(e)) {
          return null;
        }
        throw e;
      }
    },

    didStat(params) {
      let { did } = params;
      did = did.split("#")[0];
      return resolveDidRuntime(did).resolver.stat(did);
    },

    generatePublicKeyBase58(params) {
      let { privateKey } = params;
      if (!privateKey) {
        throw new Error("Private key is required for public key generation");
      }
      if (privateKey.length === 66) {
        privateKey = privateKey.substring(2);
      }

      const keypair = Secp256k1DidKeypair.fromPrivateKey(privateKey);
      return {
        publicKeyBase58: keypair.base58PublicKey(),
        type: keypair.type()
      };
    },

    // did_issueCredential 的钱包侧实现：DApp 端调用 @jccdex/did 的 issueVC，
    // 把 sign(data) 回调委托给钱包；data = { credential, keyDoc:{address,did,id},
    // compactProof, issuerObject, addSuiteContext, type }（documentLoader 在 JSON
    // 序列化时被丢弃）。这里用私钥补齐 keyDoc 后跑完整 issueCredential 返回签名 VC。
    // 自定义 @context 均以内联对象形式嵌在 credential 中，默认 documentLoader 即可。
    async signCredential(params) {
      let {
        credential,
        keyDoc,
        compactProof,
        issuerObject,
        addSuiteContext,
        type,
        privateKey
      } = params;

      if (!credential) {
        throw new Error("credential is required");
      }
      if (!keyDoc || !keyDoc.did || !keyDoc.id) {
        throw new Error("keyDoc with did and id is required");
      }
      if (typeof issueCredential !== "function") {
        throw new Error("issueCredential is not available in the DID runtime");
      }
      if (typeof credential === "string") {
        credential = JSON.parse(credential);
      }

      privateKey = normalizePrivateKey(privateKey);
      const didKp = Secp256k1DidKeypair.fromPrivateKey(privateKey);
      const rawKp = didKp.keypair();
      const realKeyDoc = getKeyDoc(
        keyDoc.did,
        rawKp,
        keyDoc.type || didKp.type(),
        keyDoc.id
      );

      const signed = await issueCredential(
        realKeyDoc,
        credential,
        compactProof !== false,
        null, // documentLoader：自定义 context 内联，默认 loader 可处理 W3C/套件 URL
        null, // purpose
        undefined, // expansionMap
        issuerObject || null,
        addSuiteContext !== false,
        type || null
      );
      return signed;
    },

    // ipfs_getPublicKey：返回压缩 secp256k1 公钥（hex）。
    ipfsGetPublicKey(params) {
      const privateKey = normalizePrivateKey(params.privateKey);
      const rawKp = Secp256k1DidKeypair.fromPrivateKey(privateKey).keypair();
      return bytesToHex(rawKp.publicKey().secp256k1);
    },

    // ipfs_personalSign：对 "\x19IPFS Signed Message:\n" + len + data 做 SHA-256，
    // 再用 secp256k1 私钥签名，输出 DER(hex)。canonical 保证 low-S，便于后端校验。
    ipfsPersonalSign(params) {
      const privateKey = normalizePrivateKey(params.privateKey);
      const data = toUint8Array(params.data);

      const prefix = new TextEncoder().encode(
        "\u0019IPFS Signed Message:\n" + data.length
      );
      const prefixed = new Uint8Array(prefix.length + data.length);
      prefixed.set(prefix, 0);
      prefixed.set(data, prefix.length);

      const rawKp = Secp256k1DidKeypair.fromPrivateKey(privateKey).keypair();
      const hash = rawKp.constructor.hash(prefixed);
      return rawKp.keyPair.sign(hash, { canonical: true }).toDER("hex");
    },

    async generateVC(params) {
      let { id, types, subject, privateKey, address, did, expirationDate, contextType } = params;
      if (!privateKey) {
        throw new Error("Private key is required for VC generation");
      }

      let runtime;
      let didString = did;
      if (didString) {
        runtime = resolveDidRuntime(didString);
      } else if (ethWallet.isValidAddress(address)) {
        const didObject = EthrDid.fromIdentifier(address);
        didString = didObject.toString();
        runtime = {
          didObject,
          resolver: ethrResolver,
          publisher: ethrPublisher,
          nftContext: ETHER_NFTOWNERSHIP_CONTEXT
        };
      } else if (jtWallet.isValidAddress(address)) {
        const didObject = SwtcDid.fromIdentifier(address);
        didString = didObject.toString();
        runtime = {
          didObject,
          resolver: swtcResolver,
          publisher: swtcPublisher,
          nftContext: SWTC_NFTOWNERSHIP_CONTEXT
        };
      } else {
        throw new Error("Invalid address for DID generation");
      }

      if (privateKey.length === 66) {
        privateKey = privateKey.substring(2);
      }

      const keypair = Secp256k1DidKeypair.fromPrivateKey(privateKey);
      const rawKp = keypair.keypair();
      didString = didString || runtime.didObject.toString();
      const keyDoc = getKeyDoc(didString, rawKp, keypair.type(), didString + "#key-1");
      const vc = new BaseNftVC();
      vc.setId(id);
      const nftContext =
        contextType === "usageAuthorization"
          ? runtime.resolver === swtcResolver
            ? SWTC_NFT_USAGE_AUTHORIZATION_CONTEXT
            : ETHER_NFT_USAGE_AUTHORIZATION_CONTEXT
          : runtime.nftContext;
      vc.addContext(nftContext);

      for (const type of types) {
        vc.addType(type);
      }

      vc.setSubject(subject);
      if (expirationDate) {
        vc.setExpirationDate(expirationDate);
      }

      await vc.sign({ keyDoc });
      return JSON.stringify(vc.toJSON());
    },

    async verifyCredential(params) {
      const { credential } = params;
      if (!credential) {
        throw new Error("Credential is required");
      }

      const credentialJson = typeof credential === "string" ? JSON.parse(credential) : credential;
      const ownerDid = credentialJson.id?.split("#nft")[0];
      if (!ownerDid) {
        throw new Error("Invalid credential id");
      }

      const runtime = resolveDidRuntime(ownerDid);
      const vc = BaseNftVC.fromJSON(credentialJson);
      return await vc.verify({ resolver: runtime.resolver });
    },

    async generateDidDoc(params) {
      const { version, authentications, assertionMethods, verificationMethods, services, credentials, did } = params;
      const runtime = resolveDidRuntime(did);
      const didDoc =
        runtime.resolver === swtcResolver
          ? new SwtcDidDocument(did)
          : new EthrDidDocument(did);

      if (version) {
        didDoc.setVersion(version);
      }
      if (authentications) {
        for (const auth of authentications) {
          didDoc.addAuthentication(auth);
        }
      }
      if (assertionMethods) {
        for (const assertion of assertionMethods) {
          didDoc.addAssertionMethod(assertion);
        }
      }
      if (verificationMethods) {
        for (const vm of verificationMethods) {
          didDoc.addVerificationMethod(vm);
        }
      }
      if (services) {
        for (const svc of services) {
          didDoc.addService(svc);
        }
      }
      if (Array.isArray(credentials)) {
        for (const cred of credentials) {
          didDoc.addCredential(cred);
        }
      }

      const serialized = didDoc.toJSON();
      if (!Array.isArray(serialized.credentials)) {
        serialized.credentials = [];
      }
      return serialized;
    }
  };

  global.PromiseBridge = {
    call: async function (method, params, id) {
      try {
        if (!method || typeof method !== "string") {
          throw new Error("invalid method");
        }
        const fn = methods[method];
        if (!fn) {
          throw new Error("no such method: " + method);
        }

        const result = await fn(params);
        window.JSBridge.onPromiseResult(id, JSON.stringify({ result }));
      } catch (e) {
        const error = e && e.message ? e.message : String(e);
        window.JSBridge.onPromiseResult(id, JSON.stringify({ error }));
      }
    }
  };
})(window);
