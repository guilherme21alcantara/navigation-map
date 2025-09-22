package com.eopeter.fluttermapboxnavigation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.eopeter.fluttermapboxnavigation.activity.NavigationLauncher
import com.eopeter.fluttermapboxnavigation.factory.EmbeddedNavigationViewFactory
import com.eopeter.fluttermapboxnavigation.models.Waypoint
import com.mapbox.api.directions.v5.DirectionsCriteria
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformViewRegistry
class FlutterMapboxNavigationPlugin : FlutterPlugin, MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler, ActivityAware {

    private lateinit var channel: MethodChannel
    private lateinit var progressEventChannel: EventChannel
    private var currentActivity: Activity? = null
    private lateinit var currentContext: Context

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        val messenger = binding.binaryMessenger
        channel = MethodChannel(messenger, "flutter_mapbox_navigation")
        channel.setMethodCallHandler(this)

        progressEventChannel = EventChannel(messenger, "flutter_mapbox_navigation/events")
        progressEventChannel.setStreamHandler(this)

        platformViewRegistry = binding.platformViewRegistry
        binaryMessenger = messenger
    }

    companion object {
        var eventSink: EventChannel.EventSink? = null
        var PERMISSION_REQUEST_CODE: Int = 367
        lateinit var routes: List<com.mapbox.api.directions.v5.models.DirectionsRoute>
        private var currentRoute: com.mapbox.api.directions.v5.models.DirectionsRoute? = null
        val wayPoints: MutableList<Waypoint> = mutableListOf()

        var showAlternateRoutes: Boolean = true
        var longPressDestinationEnabled: Boolean = true
        var allowsUTurnsAtWayPoints: Boolean = false
        var enableOnMapTapCallback: Boolean = false
        var navigationMode = DirectionsCriteria.PROFILE_DRIVING_TRAFFIC
        var simulateRoute = false
        var enableFreeDriveMode = false
        var mapStyleUrlDay: String? = null
        var mapStyleUrlNight: String? = null
        var navigationLanguage = "en"
        var navigationVoiceUnits = DirectionsCriteria.IMPERIAL
        var voiceInstructionsEnabled = true
        var bannerInstructionsEnabled = true
        var zoom = 25.0
        var bearing = 0.0
        var tilt = 0.0
        var distanceRemaining: Float? = null
        var durationRemaining: Double? = null
        var platformViewRegistry: PlatformViewRegistry? = null
        var binaryMessenger: BinaryMessenger? = null

        var viewId = "FlutterMapboxNavigationView"
    }

    // ---------- ActivityAware ----------

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        currentActivity = binding.activity
        currentContext = binding.activity.applicationContext
        if (platformViewRegistry != null && binaryMessenger != null && currentActivity != null) {
            platformViewRegistry?.registerViewFactory(
                viewId,
                EmbeddedNavigationViewFactory(binaryMessenger!!, currentActivity!!)
            )
        }
    }

    override fun onDetachedFromActivity() {
        cleanupActivityReferences()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        currentActivity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        cleanupActivityReferences()
    }

    private fun cleanupActivityReferences() {
        currentActivity = null
        eventSink = null
        if (::channel.isInitialized) channel.setMethodCallHandler(null)
        if (::progressEventChannel.isInitialized) progressEventChannel.setStreamHandler(null)
    }

    // ---------- FlutterPlugin ----------

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        cleanupActivityReferences()
        binaryMessenger = null
        platformViewRegistry = null
    }

    // ---------- MethodCallHandler ----------

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        // seu código atual de onMethodCall permanece aqui
    }

    override fun onListen(args: Any?, events: EventChannel.EventSink?) {
        eventSink = events
    }

    override fun onCancel(args: Any?) {
        eventSink = null
    }
}
