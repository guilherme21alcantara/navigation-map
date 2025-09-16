package com.eopeter.fluttermapboxnavigation

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.location.Location
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import com.eopeter.fluttermapboxnavigation.databinding.NavigationActivityBinding
import com.eopeter.fluttermapboxnavigation.models.MapBoxEvents
import com.eopeter.fluttermapboxnavigation.models.MapBoxRouteProgressEvent
import com.eopeter.fluttermapboxnavigation.models.Waypoint
import com.eopeter.fluttermapboxnavigation.models.WaypointSet
import com.eopeter.fluttermapboxnavigation.utilities.CustomInfoPanelEndNavButtonBinder
import com.eopeter.fluttermapboxnavigation.utilities.PluginUtilities
import com.google.gson.Gson
import com.mapbox.maps.Style
import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.base.extensions.applyLanguageAndVoiceUnitOptions
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.route.NavigationRouterCallback
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.base.route.RouterOrigin
import com.mapbox.navigation.base.trip.model.RouteLegProgress
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.arrival.ArrivalObserver
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.trip.session.*
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.*

open class TurnByTurn(
    ctx: Context,
    act: Activity,
    bind: NavigationActivityBinding,
    accessToken: String
) : MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler,
    Application.ActivityLifecycleCallbacks {

    private val context: Context = ctx
    val activity: Activity = act
    private val token: String = accessToken
    open val binding: NavigationActivityBinding = bind
    open var methodChannel: MethodChannel? = null
    open var eventChannel: EventChannel? = null
    private var lastLocation: Location? = null

    private val addedWaypoints = WaypointSet()

    private var initialLatitude: Double? = null
    private var initialLongitude: Double? = null
    private var navigationMode = DirectionsCriteria.PROFILE_DRIVING_TRAFFIC
    var simulateRoute = false
    private var mapStyleUrlDay: String? = null
    private var mapStyleUrlNight: String? = null
    private var navigationLanguage = "en"
    private var navigationVoiceUnits = DirectionsCriteria.IMPERIAL
    private var zoom = 15.0
    private var bearing = 0.0
    private var tilt = 0.0
    private var distanceRemaining: Float? = null
    private var durationRemaining: Double? = null

    private var alternatives = true
    var allowsUTurnAtWayPoints = false
    var enableRefresh = false
    private var voiceInstructionsEnabled = true
    private var bannerInstructionsEnabled = true
    private var longPressDestinationEnabled = true
    private var enableOnMapTapCallback = false
    private var animateBuildRoute = true
    private var isOptimized = false

    private var currentRoutes: List<NavigationRoute>? = null
    private var isNavigationCanceled = false

    open fun initFlutterChannelHandlers() {
        this.methodChannel?.setMethodCallHandler(this)
        this.eventChannel?.setStreamHandler(this)
    }

    open fun initNavigation() {
        val navigationOptions = NavigationOptions.Builder(this.context)
            .accessToken(this.token)
            .build()

        MapboxNavigationApp
            .setup(navigationOptions)
            .attach(this.activity as LifecycleOwner)
    
        registerObservers()
    }

    override fun onMethodCall(methodCall: MethodCall, result: MethodChannel.Result) {
        when (methodCall.method) {
            "getPlatformVersion" -> result.success("Android ${android.os.Build.VERSION.RELEASE}")
            "buildRoute" -> buildRoute(methodCall, result)
            "clearRoute" -> clearRoute(methodCall, result)
            "startFreeDrive" -> {
                FlutterMapboxNavigationPlugin.enableFreeDriveMode = true
                startFreeDrive()
            }
            "startNavigation" -> {
                FlutterMapboxNavigationPlugin.enableFreeDriveMode = false
                startNavigation(methodCall, result)
            }
            "finishNavigation" -> finishNavigation(methodCall, result)
            "getDistanceRemaining" -> result.success(this.distanceRemaining)
            "getDurationRemaining" -> result.success(this.durationRemaining)
            else -> result.notImplemented()
        }
    }

    private fun buildRoute(methodCall: MethodCall, result: MethodChannel.Result) {
        this.isNavigationCanceled = false

        val arguments = methodCall.arguments as? Map<*, *>
        if (arguments != null) this.setOptions(arguments)
        this.addedWaypoints.clear()
        val points = arguments?.get("wayPoints") as HashMap<*, *>
        for (item in points) {
            val point = item.value as HashMap<*, *>
            val latitude = point["Latitude"] as Double
            val longitude = point["Longitude"] as Double
            val isSilent = point["IsSilent"] as Boolean
            this.addedWaypoints.add(Waypoint(Point.fromLngLat(longitude, latitude), isSilent))
        }
        this.getRoute(this.context)
        result.success(true)
    }

    private fun getRoute(context: Context) {
        MapboxNavigationApp.current()!!.requestRoutes(
            routeOptions = RouteOptions
                .builder()
                .applyDefaultNavigationOptions(navigationMode)
                .applyLanguageAndVoiceUnitOptions(context)
                .coordinatesList(this.addedWaypoints.coordinatesList())
                .waypointIndicesList(this.addedWaypoints.waypointsIndices())
                .waypointNamesList(this.addedWaypoints.waypointsNames())
                .language(navigationLanguage)
                .alternatives(alternatives)
                .steps(true)
                .voiceUnits(navigationVoiceUnits)
                .bannerInstructions(bannerInstructionsEnabled)
                .voiceInstructions(voiceInstructionsEnabled)
                .build(),
            callback = object : NavigationRouterCallback {
                override fun onRoutesReady(routes: List<NavigationRoute>, routerOrigin: RouterOrigin) {
                    this@TurnByTurn.currentRoutes = routes
                    PluginUtilities.sendEvent(
                        MapBoxEvents.ROUTE_BUILT,
                        Gson().toJson(routes.map { it.directionsRoute.toJson() })
                    )
                    this@TurnByTurn.binding.navigationView.api.routeReplayEnabled(this@TurnByTurn.simulateRoute)
                    this@TurnByTurn.binding.navigationView.api.startRoutePreview(routes)
                    this@TurnByTurn.binding.navigationView.customizeViewBinders {
                        this.infoPanelEndNavigationButtonBinder =
                            CustomInfoPanelEndNavButtonBinder(activity)
                    }
                }

                override fun onFailure(reasons: List<RouterFailure>, routeOptions: RouteOptions) {
                    PluginUtilities.sendEvent(MapBoxEvents.ROUTE_BUILD_FAILED)
                }

                override fun onCanceled(routeOptions: RouteOptions, routerOrigin: RouterOrigin) {
                    PluginUtilities.sendEvent(MapBoxEvents.ROUTE_BUILD_CANCELLED)
                }
            }
        )
    }

   private fun clearRoute(methodCall: MethodCall, result: MethodChannel.Result) {
    this.currentRoutes = null
    this.binding.navigationView.api.startFreeDrive()
    PluginUtilities.sendEvent(MapBoxEvents.NAVIGATION_CANCELLED)
    result.success(true)
}


    private fun startFreeDrive() {
        this.binding.navigationView.api.startFreeDrive()
    }

    private fun startNavigation(methodCall: MethodCall, result: MethodChannel.Result) {
        val arguments = methodCall.arguments as? Map<*, *>
        if (arguments != null) this.setOptions(arguments)
        startNavigation()
        result.success(this.currentRoutes != null)
    }

    private fun finishNavigation(methodCall: MethodCall, result: MethodChannel.Result) {
        finishNavigation()
        result.success(this.currentRoutes != null)
    }

    @SuppressLint("MissingPermission")
    private fun startNavigation() {
        if (this.currentRoutes == null) {
            PluginUtilities.sendEvent(MapBoxEvents.NAVIGATION_CANCELLED)
            return
        }
        this.binding.navigationView.api.startActiveGuidance(this.currentRoutes!!)
        PluginUtilities.sendEvent(MapBoxEvents.NAVIGATION_RUNNING)
    }

private fun finishNavigation(isOffRouted: Boolean = false) {
    this.binding.navigationView.api.startFreeDrive()
    this.isNavigationCanceled = true
    PluginUtilities.sendEvent(MapBoxEvents.NAVIGATION_CANCELLED)
}

    private fun setOptions(arguments: Map<*, *>) {
        val navMode = arguments["mode"] as? String
        if (navMode != null) {
            navigationMode = when (navMode) {
                "walking" -> DirectionsCriteria.PROFILE_WALKING
                "cycling" -> DirectionsCriteria.PROFILE_CYCLING
                else -> DirectionsCriteria.PROFILE_DRIVING
            }
        }

        simulateRoute = arguments["simulateRoute"] as? Boolean ?: false
        navigationLanguage = arguments["language"] as? String ?: "en"
        navigationVoiceUnits = when (arguments["units"] as? String) {
            "imperial" -> DirectionsCriteria.IMPERIAL
            "metric" -> DirectionsCriteria.METRIC
            else -> DirectionsCriteria.IMPERIAL
        }

        mapStyleUrlDay = arguments["mapStyleUrlDay"] as? String ?: Style.MAPBOX_STREETS
        mapStyleUrlNight = arguments["mapStyleUrlNight"] as? String ?: Style.DARK

        binding.navigationView.customizeViewOptions {
            mapStyleUriDay = mapStyleUrlDay
            mapStyleUriNight = mapStyleUrlNight
        }

        initialLatitude = arguments["initialLatitude"] as? Double
        initialLongitude = arguments["initialLongitude"] as? Double
        zoom = arguments["zoom"] as? Double ?: 15.0
        bearing = arguments["bearing"] as? Double ?: 0.0
        tilt = arguments["tilt"] as? Double ?: 0.0
        isOptimized = arguments["isOptimized"] as? Boolean ?: false
        animateBuildRoute = arguments["animateBuildRoute"] as? Boolean ?: true
        alternatives = arguments["alternatives"] as? Boolean ?: true
        voiceInstructionsEnabled = arguments["voiceInstructionsEnabled"] as? Boolean ?: true
        bannerInstructionsEnabled = arguments["bannerInstructionsEnabled"] as? Boolean ?: true
        longPressDestinationEnabled = arguments["longPressDestinationEnabled"] as? Boolean ?: true
        enableOnMapTapCallback = arguments["enableOnMapTapCallback"] as? Boolean ?: false
    }

    open fun registerObservers() {
        MapboxNavigationApp.current()?.registerBannerInstructionsObserver(this.bannerInstructionObserver)
        MapboxNavigationApp.current()?.registerVoiceInstructionsObserver(this.voiceInstructionObserver)
        MapboxNavigationApp.current()?.registerOffRouteObserver(this.offRouteObserver)
        MapboxNavigationApp.current()?.registerRoutesObserver(this.routesObserver)
        MapboxNavigationApp.current()?.registerLocationObserver(this.locationObserver)
        MapboxNavigationApp.current()?.registerRouteProgressObserver(this.routeProgressObserver)
        MapboxNavigationApp.current()?.registerArrivalObserver(this.arrivalObserver)
    }

    open fun unregisterObservers() {
        MapboxNavigationApp.current()?.unregisterBannerInstructionsObserver(this.bannerInstructionObserver)
        MapboxNavigationApp.current()?.unregisterVoiceInstructionsObserver(this.voiceInstructionObserver)
        MapboxNavigationApp.current()?.unregisterOffRouteObserver(this.offRouteObserver)
        MapboxNavigationApp.current()?.unregisterRoutesObserver(this.routesObserver)
        MapboxNavigationApp.current()?.unregisterLocationObserver(this.locationObserver)
        MapboxNavigationApp.current()?.unregisterRouteProgressObserver(this.routeProgressObserver)
        MapboxNavigationApp.current()?.unregisterArrivalObserver(this.arrivalObserver)
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        FlutterMapboxNavigationPlugin.eventSink = events
    }

    override fun onCancel(arguments: Any?) {
        FlutterMapboxNavigationPlugin.eventSink = null
    }

    private val locationObserver = object : LocationObserver {
        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            this@TurnByTurn.lastLocation = locationMatcherResult.enhancedLocation
        }
        override fun onNewRawLocation(rawLocation: Location) {}
    }

    private val bannerInstructionObserver = BannerInstructionsObserver { bannerInstructions ->
        PluginUtilities.sendEvent(MapBoxEvents.BANNER_INSTRUCTION, bannerInstructions.primary().text())
    }

    private val voiceInstructionObserver = VoiceInstructionsObserver { voiceInstructions ->
        PluginUtilities.sendEvent(MapBoxEvents.SPEECH_ANNOUNCEMENT, voiceInstructions.announcement().toString())
    }

    private val offRouteObserver = OffRouteObserver { offRoute ->
        if (offRoute) PluginUtilities.sendEvent(MapBoxEvents.USER_OFF_ROUTE)
    }

    private val routesObserver = RoutesObserver { routeUpdateResult ->
        if (routeUpdateResult.navigationRoutes.isNotEmpty()) {
            PluginUtilities.sendEvent(MapBoxEvents.REROUTE_ALONG)
        }
    }

    private val routeProgressObserver = RouteProgressObserver { routeProgress ->
        if (!isNavigationCanceled) {
            try {
                distanceRemaining = routeProgress.distanceRemaining
                durationRemaining = routeProgress.durationRemaining
                PluginUtilities.sendEvent(MapBoxRouteProgressEvent(routeProgress))
            } catch (_: Exception) {}
        }
    }

    private val arrivalObserver: ArrivalObserver = object : ArrivalObserver {
    override fun onFinalDestinationArrival(routeProgress: RouteProgress) {
        this@TurnByTurn.binding.navigationView.api.startFreeDrive()
        PluginUtilities.sendEvent(MapBoxEvents.ON_ARRIVAL)
    }
    override fun onNextRouteLegStart(routeLegProgress: RouteLegProgress) {}
    override fun onWaypointArrival(routeProgress: RouteProgress) {}
}


    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
   override fun onActivityDestroyed(activity: Activity) {
    if (activity != this.activity) return // só limpa se for a activity real do app
    if (!isNavigationCanceled) return      // não desmonta se só finalizou navegação

    unregisterObservers()
    methodChannel?.setMethodCallHandler(null)
    eventChannel?.setStreamHandler(null)
}

}
