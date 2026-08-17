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
            setGoalNotifications: function(payload) { return invoke('setGoalNotifications', payload); },
            markGoalProgressAddressed: function(payload) { return invoke('markGoalProgressAddressed', payload); },
            getAppInfo: function() { return invoke('getAppInfo'); },
            secureStoreToken: function(token) { return invoke('secureStoreToken', { token: token }); },
            secureStoreSession: function(payload) { return invoke('secureStoreSession', payload); },
            getSecureSession: function() { return invoke('getSecureSession'); },
            refreshAuthToken: function() { return invoke('refreshAuthToken'); },
            clearSecureToken: function() { return invoke('clearSecureToken'); },
            saveAppState: function(payload) { return invoke('saveAppState', payload); },
            getAppState: function() { return invoke('getAppState'); },
            clearAppState: function() { return invoke('clearAppState'); },
            openPayment: function(payload) { return invoke('openPayment', payload); },
            openUrl: function(payload) { return invoke('openUrl', payload); },
            share: function(payload) { return invoke('share', payload); },
            setBackPressBehavior: function(payload) { return invoke('setBackPressBehavior', payload); }
          };
          function buildAutoStatePayload(extraState) {
            return {
              url: String(window.location.href || ''),
              pathname: String(window.location.pathname || ''),
              search: String(window.location.search || ''),
              hash: String(window.location.hash || ''),
              title: String(document.title || ''),
              scrollX: Number(window.scrollX || 0),
              scrollY: Number(window.scrollY || 0),
              capturedAt: new Date().toISOString(),
              state: extraState || null
            };
          }
          function captureCustomState() {
            try {
              var capture = window.VectorMobileCallbacks && window.VectorMobileCallbacks.captureAppState;
              return typeof capture === 'function' ? capture() : null;
            } catch (error) {
              return { captureError: String(error && error.message ? error.message : error) };
            }
          }
          function saveNativeAppState() {
            try {
              Promise.resolve(captureCustomState()).then(function(extraState) {
                window.VectorMobileBridge.saveAppState(buildAutoStatePayload(extraState));
              });
            } catch (_) {}
          }
          if (!window.__VectorMobileAutoStateInstalled) {
            window.__VectorMobileAutoStateInstalled = true;
            window.VectorMobileBridge.saveCurrentState = saveNativeAppState;
            document.addEventListener('visibilitychange', function() {
              if (document.visibilityState === 'hidden') saveNativeAppState();
            });
            window.addEventListener('pagehide', saveNativeAppState);
            window.addEventListener('beforeunload', saveNativeAppState);
            window.setInterval(saveNativeAppState, 15000);
          }
          window.dispatchEvent(new CustomEvent('VectorMobileBridgeReady'));
        })();
    """.trimIndent()
}
