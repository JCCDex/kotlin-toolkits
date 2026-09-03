// Shared PromiseBridge installer for unified-bridge.html (P2-8b).
// wallet-bridge.js and did-bridge.js register methods on __BridgeMethods; this installs PromiseBridge once.

(function (global) {
  global.__BridgeMethods = global.__BridgeMethods || {};

  global.__installPromiseBridge = function () {
    const registry = global.__BridgeMethods;
    global.PromiseBridge = {
      call: async function (method, params, id) {
        try {
          if (!method || typeof method !== "string") {
            throw new Error("invalid method");
          }
          const fn = registry[method];
          if (!fn) {
            throw new Error("no such method: " + method);
          }
          const result = await fn(params);
          if (window.JSBridge && window.JSBridge.onPromiseResult) {
            window.JSBridge.onPromiseResult(id, JSON.stringify({ result: result }));
          }
        } catch (err) {
          const message = err && err.message ? err.message : String(err);
          if (window.JSBridge && window.JSBridge.onPromiseResult) {
            window.JSBridge.onPromiseResult(id, JSON.stringify({ error: message }));
          }
        }
      }
    };
  };

  global.__notifyBridgeReady = function () {
    if (window.JSBridge && window.JSBridge.onBridgeReady) {
      try {
        window.JSBridge.onBridgeReady();
      } catch (_) {}
    }
  };
})(window);
