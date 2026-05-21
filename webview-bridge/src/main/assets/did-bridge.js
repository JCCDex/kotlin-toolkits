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
    ETHER_NFTOWNERSHIP_CONTEXT,
    SWTC_NFTOWNERSHIP_CONTEXT,
    ETHER_NFT_USAGE_AUTHORIZATION_CONTEXT,
    SWTC_NFT_USAGE_AUTHORIZATION_CONTEXT
  } = window.jcc_did;

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
      const { did } = params;
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
      const { did } = params;
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
      if (credentials) {
        for (const cred of credentials) {
          didDoc.addCredential(cred);
        }
      }

      return didDoc.toJSON();
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
