#import <Foundation/Foundation.h>
#import <Capacitor/Capacitor.h>

// Define the plugin using the CAP_PLUGIN Macro, and
// each method the plugin supports using the CAP_PLUGIN_METHOD macro.
// All methods that are callable from JS must be listed here or the bridge returns UNIMPLEMENTED.
CAP_PLUGIN(CallKitVoipPlugin, "CallKitVoip",
           CAP_PLUGIN_METHOD(register, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(getVoipToken, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(callConnected, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(endCall, CAPPluginReturnPromise);
)

