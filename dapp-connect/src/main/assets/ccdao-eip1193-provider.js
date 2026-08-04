/**
 * CCDAO EIP-1193 Provider - 纯 JavaScript 实现，不依赖 trustwallet
 * 通过 window._tw_ 与 Native 代码通信
 */
(function() {
    // 防止重复注入
    if (window.ethereum && window.ethereum._ccdaoProvider) {
        return;
    }

    // 全局状态
    if (!window._ccdaoProviderState) {
        window._ccdaoProviderState = {
            chainId: '0x1',
            chainIdDecimal: 1,
            accounts: [],
            listeners: {}
        };
    }
    const state = window._ccdaoProviderState;

    // 请求队列留在 IIFE 闭包内（C-03）：不再挂 window，避免页面脚本伪造/劫持回调。
    const requestQueue = {};
    let requestId = 0;
    let nativePort = null;
    const NATIVE_PORT_HANDSHAKE = '__CCDAO_NATIVE_PORT__';

    function settleRequest(nonce, response) {
        const callback = requestQueue[nonce];
        if (!callback) {
            return;
        }
        delete requestQueue[nonce];
        callback(response);
    }

    function acceptNativePort(port) {
        if (nativePort) {
            try { nativePort.close(); } catch (_) {}
        }
        nativePort = port;
        port.onmessage = function(event) {
            let msg = event.data;
            if (typeof msg === 'string') {
                try { msg = JSON.parse(msg); } catch (_) { return; }
            }
            if (!msg || typeof msg.nonce !== 'string') {
                return;
            }
            if (Object.prototype.hasOwnProperty.call(msg, 'error')) {
                settleRequest(msg.nonce, { error: msg.error });
            } else {
                settleRequest(msg.nonce, { result: msg.result });
            }
        };
    }

    window.addEventListener('message', function(event) {
        if (event.data === NATIVE_PORT_HANDSHAKE && event.ports && event.ports[0]) {
            acceptNativePort(event.ports[0]);
        }
    });

    // ipfs_personalSign 的首个参数是二进制（ArrayBuffer/TypedArray），
    // 直接 JSON.stringify 会丢失（ArrayBuffer→{}），统一归一化为普通字节数组传给原生。
    function normalizeBinaryParams(method, params) {
        if (method !== 'ipfs_personalSign' || !Array.isArray(params) || params.length === 0) {
            return params;
        }
        const d = params[0];
        let u8 = null;
        if (d instanceof ArrayBuffer) {
            u8 = new Uint8Array(d);
        } else if (ArrayBuffer.isView(d)) {
            u8 = new Uint8Array(d.buffer, d.byteOffset, d.byteLength);
        } else if (Array.isArray(d)) {
            u8 = Uint8Array.from(d);
        } else if (d && typeof d === 'object') {
            u8 = Uint8Array.from(Object.values(d));
        }
        if (!u8) {
            return params;
        }
        return [Array.from(u8), ...params.slice(1)];
    }

    // crypto.randomUUID() polyfill
    function randomUUID() {
        return crypto.randomUUID?.() ??
            'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
                const r = crypto.getRandomValues(new Uint8Array(1))[0];
                return (c === 'x' ? r & 15 : (r & 0x3 | 0x8)).toString(16);
            });
    }

    // 发送请求到 Native（响应经 WebMessagePort 回传，不再暴露 window.ccdao.sendResponse）
    function sendToNative(method, params, callback) {
        const id = ++requestId;
        const nonce = randomUUID();
        requestQueue[nonce] = callback;
        params = normalizeBinaryParams(method, params);

        const message = JSON.stringify({
            name: method,
            network: method.startsWith('swtc_') ? 'swtc' :
                     method.startsWith('eth_') || method.startsWith('wallet_') || method.startsWith('personal_') ? 'eth' : 'ccdao',
            id: String(id),
            nonce: nonce,
            params: params || []
        });

        if (window._tw_ && window._tw_.postMessage) {
            window._tw_.postMessage(message);
        } else {
            console.error('[CCDAO EIP-1193] _tw_ not available');
            delete requestQueue[nonce];
            callback({ error: 'Bridge not available' });
        }
    }

    // EIP-1193 Provider
    const provider = {
        _ccdaoProvider: true,
        isMetaMask: true,

        // 当前链 ID（动态属性）
        get chainId() {
            return state.chainId;
        },

        // 当前网络版本（动态属性）
        get networkVersion() {
            return String(state.chainIdDecimal);
        },

        // 当前选中地址
        get selectedAddress() {
            return state.accounts[0] || null;
        },

        // 检查是否已连接
        isConnected: function() {
            return state.accounts.length > 0;
        },

        // EIP-1193 request 方法
        request: function(args) {
            const method = args.method;
            const params = args.params || [];

            // 拦截 eth_chainId，返回当前状态
            if (method === 'eth_chainId') {
                return Promise.resolve(state.chainId);
            }

            // 拦截 eth_accounts，返回当前账户
            if (method === 'eth_accounts') {
                return Promise.resolve(state.accounts);
            }

            // 拦截 eth_requestAccounts
            if (method === 'eth_requestAccounts') {
                return new Promise((resolve, reject) => {
                    sendToNative('eth_requestAccounts', [], (response) => {
                        if (response.error) {
                            reject(response.error);
                        } else {
                            state.accounts = response.result || [];
                            resolve(state.accounts);
                        }
                    });
                });
            }

            // 其他请求转发到 Native
            return new Promise((resolve, reject) => {
                sendToNative(method, params, (response) => {
                    if (response.error) {
                        reject(response.error);
                        // 如果链不支持，触发错误
                        if (method === 'wallet_switchEthereumChain' && response.error.code === 4902) {
                            provider._emitError('wallet_switchEthereumChain', response.error);
                        }
                    } else {
                        resolve(response.result);
                    }
                });
            });
        },

        // EIP-1193 事件系统
        on: function(event, handler) {
            if (!state.listeners[event]) {
                state.listeners[event] = [];
            }
            state.listeners[event].push(handler);
        },

        removeListener: function(event, handler) {
            if (state.listeners[event]) {
                const index = state.listeners[event].indexOf(handler);
                if (index > -1) {
                    state.listeners[event].splice(index, 1);
                }
            }
        },

        removeAllListeners: function(event) {
            if (event) {
                delete state.listeners[event];
            } else {
                state.listeners = {};
            }
        },

        // 内部 emit 方法
        emit: function(event, data) {
            const handlers = state.listeners[event];
            if (handlers) {
                handlers.forEach(handler => {
                    try {
                        handler(data);
                    } catch (e) {
                        console.error('[CCDAO EIP-1193] Handler error:', e);
                    }
                });
            }
        },

        _emitError: function(method, error) {
        }
    };

    // 更新链 ID 的全局函数
    window._updateChainId = function(newChainIdHex, newRpcUrl) {
        const oldChainId = state.chainId;
        state.chainId = newChainIdHex;
        state.chainIdDecimal = parseInt(newChainIdHex, 16);

        // 触发 chainChanged 事件
        if (oldChainId !== newChainIdHex) {
            provider.emit('chainChanged', newChainIdHex);
        }

        // 更新 RPC URL（如果需要）
        if (newRpcUrl) {
            state.rpcUrl = newRpcUrl;
        }
    };

    // 更新选中地址的全局函数（地址未变时不触发 accountsChanged，避免 WebView 重复注入误触发 DApp 刷新）
    window._updateSelectedAddress = function(address) {
        if (!address) return;
        const oldAddress = state.accounts[0];
        state.accounts = [address];
        if (oldAddress !== address) {
            provider.emit('accountsChanged', [address]);
        }
    };

    // SWTC 账户变更：DApp 通过 window.ccdao.on('swtcAccountsChanged', ...) 监听
    // EVM 用 accountsChanged，SWTC 用 swtcAccountsChanged，两者分开避免串链。
    window._updateSwtcSelectedAddress = function(address) {
        if (!address) return;
        const oldAddress = state.accounts[0];
        state.accounts = [address];
        if (oldAddress !== address) {
            provider.emit('swtcAccountsChanged', [address]);
        }
    };

    // 设置 window.ethereum
    window.ethereum = provider;
    // 兼容性设置
    window.eth = provider;

    // ── window.ccdao：CCDAO 专有 provider ──
    // jdid 等 DApp 通过 window.ccdao.request 调用 did_/ipfs_/swtc_/eth_ 等方法。
    // 必须复用与 window.ethereum 相同的 sendToNative / 闭包 requestQueue / 事件系统（C-03：
    // 原生经 WebMessagePort 回传，不再挂 sendResponse/sendError）。
    window.ccdao = window.ccdao || {};
    window.ccdao.isCCDAO = true;
    window.ccdao.request = function(args) {
        const method = args.method;
        const params = args.params || [];
        // eth_* 与 window.ethereum 保持一致的本地拦截
        if (method === 'eth_chainId') {
            return Promise.resolve(state.chainId);
        }
        if (method === 'eth_accounts') {
            return Promise.resolve(state.accounts);
        }
        if (method === 'eth_requestAccounts') {
            return new Promise((resolve, reject) => {
                sendToNative('eth_requestAccounts', [], (response) => {
                    if (response.error) {
                        reject(response.error);
                    } else {
                        state.accounts = response.result || [];
                        resolve(state.accounts);
                    }
                });
            });
        }
        // 其余方法（swtc_/did_/ipfs_/web3_/*_requestNfts 等）直接转发原生
        return new Promise((resolve, reject) => {
            sendToNative(method, params, (response) => {
                if (response.error) {
                    reject(response.error);
                } else {
                    resolve(response.result);
                }
            });
        });
    };
    // 事件系统与 window.ethereum 共享同一份 state.listeners，
    // 因此 _updateSelectedAddress/_updateChainId 触发的 accountsChanged/chainChanged
    // 也能被通过 window.ccdao.on 注册的监听器收到。
    window.ccdao.on = provider.on;
    window.ccdao.removeListener = provider.removeListener;
    window.ccdao.removeAllListeners = provider.removeAllListeners;
    window.ccdao.emit = provider.emit;

    // EIP-6963 支持
    const providerInfo = {
        uuid: 'ccdao-connector',
        name: 'CCDAO Connector',
        icon: 'data:image/svg+xml,<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32"><circle cx="16" cy="16" r="16" fill="%234F46E5"/><text x="16" y="22" text-anchor="middle" fill="white" font-size="18" font-family="Arial">C</text></svg>',
        rdns: 'com.android.ccdaoconnector'
    };

    function announceProvider() {
        const event = new CustomEvent('eip6963:announceProvider', {
            detail: Object.freeze({
                info: Object.freeze(providerInfo),
                provider: provider
            })
        });
        window.dispatchEvent(event);
    }

    window.addEventListener('eip6963:requestProvider', announceProvider);
    announceProvider();

    // 定期广播
    setInterval(announceProvider, 5000);
})();
