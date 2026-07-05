package com.projectvector.app.bridge

object VectorBridgeInstaller {
    const val INTERFACE_NAME = "VectorNativeBridge"

    val script: String = """
        (function() {
          if (window.VectorMobileBridge && window.VectorMobileBridge.__native === true) return;
          var callbacks = {};
          var seq = 1;
          function invoke(method, payload) {
            return new Promise(function(resolve) {
              var id = String(seq++);
              callbacks[id] = resolve;
              try {
                window.$INTERFACE_NAME.postMessage(JSON.stringify({ id: id, method: method, payload: payload || null }));
              } catch (error) {
                delete callbacks[id];
                resolve({ ok: false, error: String(error && error.message ? error.message : error) });
              }
            });
          }
          window.__VectorMobileBridgeResolve = function(id, result) {
            var resolve = callbacks[id];
            if (!resolve) return;
            delete callbacks[id];
            resolve(result);
          };
          window.VectorMobileCallbacks = window.VectorMobileCallbacks || {};
          window.VectorMobileBridge = {
            __native: true,
            requestGoogleIdToken: function() { return invoke('requestGoogleIdToken'); },
            getFcmToken: function() { return invoke('getFcmToken'); },
            checkNotificationPermission: function() { return invoke('checkNotificationPermission'); },
            requestNotificationPermission: function() { return invoke('requestNotificationPermission'); },
            scheduleLocalReminder: function(payload) { return invoke('scheduleLocalReminder', payload); },
            cancelLocalReminder: function(id) { return invoke('cancelLocalReminder', { id: id }); },
            getAppInfo: function() { return invoke('getAppInfo'); },
            secureStoreToken: function(token) { return invoke('secureStoreToken', { token: token }); },
            secureStoreSession: function(payload) { return invoke('secureStoreSession', payload); },
            getSecureSession: function() { return invoke('getSecureSession'); },
            refreshAuthToken: function() { return invoke('refreshAuthToken'); },
            clearSecureToken: function() { return invoke('clearSecureToken'); },
            openPayment: function(payload) { return invoke('openPayment', payload); },
            share: function(payload) { return invoke('share', payload); },
            setBackPressBehavior: function(payload) { return invoke('setBackPressBehavior', payload); }
          };
          window.dispatchEvent(new CustomEvent('VectorMobileBridgeReady'));
        })();
    """.trimIndent()
}
