package com.eopeter.fluttermapboxnavigation.activity

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.eopeter.fluttermapboxnavigation.FlutterMapboxNavigationPlugin
import com.eopeter.fluttermapboxnavigation.R
import com.eopeter.fluttermapboxnavigation.databinding.NavigationActivityBinding
import com.eopeter.fluttermapboxnavigation.models.MapBoxEvents
import com.eopeter.fluttermapboxnavigation.models.MapBoxRouteProgressEvent
import com.eopeter.fluttermapboxnavigation.models.Waypoint
import com.eopeter.fluttermapboxnavigation.models.WaypointSet
import com.eopeter.fluttermapboxnavigation.utilities.CustomInfoPanelEndNavButtonBinder
import com.eopeter.fluttermapboxnavigation.utilities.PluginUtilities
import com.google.gson.Gson
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.geojson.Point
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.gestures.OnMapClickListener
import com.mapbox.maps.plugin.gestures.OnMapLongClickListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.base.extensions.applyLanguageAndVoiceUnitOptions
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.route.NavigationRouterCallback
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.base.route.RouterOrigin
import com.mapbox.navigation.base.trip.model.RouteLegProgress
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.arrival.ArrivalObserver
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.directions.session.RoutesUpdatedResult
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.trip.session.BannerInstructionsObserver
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.OffRouteObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.core.trip.session.VoiceInstructionsObserver
import com.mapbox.navigation.dropin.map.MapViewObserver
import com.mapbox.navigation.dropin.navigationview.NavigationViewListener
import org.json.JSONObject

class NavigationActivity : AppCompatActivity() {

    private var finishBroadcastReceiver: BroadcastReceiver? = null
    private var addWayPointsBroadcastReceiver: BroadcastReceiver? = null
    private var points: MutableList<Waypoint> = mutableListOf()
    private var waypointSet: WaypointSet = WaypointSet()
    private var isNavigationInProgress = false
    private var lastLocation: Location? = null
    private var accessToken: String? = null

    private lateinit var binding: NavigationActivityBinding

    private val navigationStateListener = object : NavigationViewListener() {
        override fun onFreeDrive() { }

        override fun onDestinationPreview() { }

        override fun onRoutePreview() { }

        override fun onActiveNavigation() {
            isNavigationInProgress = true
        }

        override fun onArrival() {
            isNavigationInProgress = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.AppTheme)
        binding = NavigationActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.navigationView.addListener(navigationStateListener)

        accessToken = PluginUtilities.getResourceFromContext(this.applicationContext, "mapbox_access_token")

        val navigationOptions = NavigationOptions.Builder(this.applicationContext)
            .accessToken(accessToken)
            .build()

        MapboxNavigationApp.setup(navigationOptions).attach(this)

        if (FlutterMapboxNavigationPlugin.longPressDestinationEnabled) {
            binding.navigationView.registerMapObserver(onMapLongClick)
            binding.navigationView.customizeViewOptions {
                enableMapLongClickIntercept = false
            }
        }

        if (FlutterMapboxNavigationPlugin.enableOnMapTapCallback) {
            binding.navigationView.registerMapObserver(onMapClick)
        }

        // Custom button binder
        binding.navigationView.customizeViewBinders {
            infoPanelEndNavigationButtonBinder = CustomInfoPanelEndNavButtonBinder(this@NavigationActivity)
        }

        // Register observers
        MapboxNavigationApp.current()?.registerBannerInstructionsObserver(bannerInstructionObserver)
        MapboxNavigationApp.current()?.registerVoiceInstructionsObserver(voiceInstructionObserver)
        MapboxNavigationApp.current()?.registerOffRouteObserver(offRouteObserver)
        MapboxNavigationApp.current()?.registerRoutesObserver(routesObserver)
        MapboxNavigationApp.current()?.registerLocationObserver(locationObserver)
        MapboxNavigationApp.current()?.registerRouteProgressObserver(routeProgressObserver)
        MapboxNavigationApp.current()?.registerArrivalObserver(arrivalObserver)

        // BroadcastReceiver para finalizar navegação
        finishBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                tryCancelNavigation()
            }
        }

        addWayPointsBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val stops = intent.getSerializableExtra("waypoints") as? MutableList<Waypoint>
                if (stops != null) points.addAll(stops)
            }
        }

        registerReceiver(finishBroadcastReceiver, IntentFilter("STOP_NAVIGATION"))
        registerReceiver(addWayPointsBroadcastReceiver, IntentFilter("ADD_WAYPOINTS"))

        // Map style
        val styleUrlDay = FlutterMapboxNavigationPlugin.mapStyleUrlDay ?: Style.MAPBOX_STREETS
        val styleUrlNight = FlutterMapboxNavigationPlugin.mapStyleUrlNight ?: Style.DARK

        binding.navigationView.customizeViewOptions {
            mapStyleUriDay = styleUrlDay
            mapStyleUriNight = styleUrlNight
        }

        // Inicia FreeDrive se habilitado
        if (FlutterMapboxNavigationPlugin.enableFreeDriveMode) {
            binding.navigationView.api.routeReplayEnabled(FlutterMapboxNavigationPlugin.simulateRoute)
            binding.navigationView.api.startFreeDrive()
            return
        }

        // Inicializa rota se existirem waypoints
        val p = intent.getSerializableExtra("waypoints") as? MutableList<Waypoint>
        if (p != null) points = p
        points.forEach { waypointSet.add(it) }
        if (points.isNotEmpty()) requestRoutes(waypointSet)
    }

    override fun onDestroy() {
        super.onDestroy()

        if (FlutterMapboxNavigationPlugin.longPressDestinationEnabled) binding.navigationView.unregisterMapObserver(onMapLongClick)
        if (FlutterMapboxNavigationPlugin.enableOnMapTapCallback) binding.navigationView.unregisterMapObserver(onMapClick)
        binding.navigationView.removeListener(navigationStateListener)

        MapboxNavigationApp.current()?.unregisterBannerInstructionsObserver(bannerInstructionObserver)
        MapboxNavigationApp.current()?.unregisterVoiceInstructionsObserver(voiceInstructionObserver)
        MapboxNavigationApp.current()?.unregisterOffRouteObserver(offRouteObserver)
        MapboxNavigationApp.current()?.unregisterRoutesObserver(routesObserver)
        MapboxNavigationApp.current()?.unregisterLocationObserver(locationObserver)
        MapboxNavigationApp.current()?.unregisterRouteProgressObserver(routeProgressObserver)
        MapboxNavigationApp.current()?.unregisterArrivalObserver(arrivalObserver)

        finishBroadcastReceiver?.let { unregisterReceiver(it); finishBroadcastReceiver = null }
        addWayPointsBroadcastReceiver?.let { unregisterReceiver(it); addWayPointsBroadcastReceiver = null }
    }

  private fun tryCancelNavigation() {
    if (!isNavigationInProgress) return  // já cancelado, não faz nada

    isNavigationInProgress = false
    binding.navigationView.api.startFreeDrive()
    PluginUtilities.sendEvent(MapBoxEvents.NAVIGATION_CANCELLED)
}


    private fun requestRoutes(waypointSet: WaypointSet) {
        PluginUtilities.sendEvent(MapBoxEvents.ROUTE_BUILDING)
        MapboxNavigationApp.current()!!.requestRoutes(
            routeOptions = RouteOptions.builder()
                .applyDefaultNavigationOptions()
                .applyLanguageAndVoiceUnitOptions(this)
                .coordinatesList(waypointSet.coordinatesList())
                .waypointIndicesList(waypointSet.waypointsIndices())
                .waypointNamesList(waypointSet.waypointsNames())
                .language(FlutterMapboxNavigationPlugin.navigationLanguage)
                .alternatives(FlutterMapboxNavigationPlugin.showAlternateRoutes)
                .voiceUnits(FlutterMapboxNavigationPlugin.navigationVoiceUnits)
                .bannerInstructions(FlutterMapboxNavigationPlugin.bannerInstructionsEnabled)
                .voiceInstructions(FlutterMapboxNavigationPlugin.voiceInstructionsEnabled)
                .steps(true)
                .build(),
            callback = object : NavigationRouterCallback {
                override fun onRoutesReady(
                    routes: List<com.mapbox.navigation.base.route.NavigationRoute>,
                    routerOrigin: RouterOrigin
                ) {
                    PluginUtilities.sendEvent(
                        MapBoxEvents.ROUTE_BUILT,
                        Gson().toJson(routes.map { it.directionsRoute.toJson() })
                    )
                    if (routes.isEmpty()) {
                        PluginUtilities.sendEvent(MapBoxEvents.ROUTE_BUILD_NO_ROUTES_FOUND)
                        binding.navigationView.api.startFreeDrive()
                        return
                    }
                    binding.navigationView.api.routeReplayEnabled(FlutterMapboxNavigationPlugin.simulateRoute)
                    binding.navigationView.api.startActiveGuidance(routes)
                }

                override fun onFailure(reasons: List<RouterFailure>, routeOptions: RouteOptions) {
                    PluginUtilities.sendEvent(MapBoxEvents.ROUTE_BUILD_FAILED)
                    binding.navigationView.api.startFreeDrive()
                }

                override fun onCanceled(routeOptions: RouteOptions, routerOrigin: RouterOrigin) {
                    PluginUtilities.sendEvent(MapBoxEvents.ROUTE_BUILD_CANCELLED)
                    binding.navigationView.api.startFreeDrive()
                }
            }
        )
    }

    // Observers
    private val routeProgressObserver = RouteProgressObserver { routeProgress ->
        FlutterMapboxNavigationPlugin.distanceRemaining = routeProgress.distanceRemaining
        FlutterMapboxNavigationPlugin.durationRemaining = routeProgress.durationRemaining
        PluginUtilities.sendEvent(MapBoxRouteProgressEvent(routeProgress))
    }

    private val arrivalObserver = object : ArrivalObserver {
        override fun onWaypointArrival(routeProgress: RouteProgress) { }

        override fun onNextRouteLegStart(routeLegProgress: RouteLegProgress) { }

        override fun onFinalDestinationArrival(routeProgress: RouteProgress) {
            isNavigationInProgress = false
            binding.navigationView.api.startFreeDrive()
            PluginUtilities.sendEvent(MapBoxEvents.ON_ARRIVAL)
        }
    }

    private val locationObserver = object : LocationObserver {
        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            lastLocation = locationMatcherResult.enhancedLocation
        }
        override fun onNewRawLocation(rawLocation: Location) { }
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

private val routesObserver = object : RoutesObserver {
    override fun onRoutesChanged(result: RoutesUpdatedResult) {
        val navigationRoutes = result.navigationRoutes

        
        if (navigationRoutes.isEmpty() && isNavigationInProgress) {
            // Evita loop infinito chamando startFreeDrive apenas se ainda estiver navegando
            isNavigationInProgress = false
            binding.navigationView.api.startFreeDrive()
            MapboxNavigationApp.current()?.setRoutes(emptyList())
            
            // Só envia evento se ainda não foi enviado
            PluginUtilities.sendEvent(MapBoxEvents.NAVIGATION_CANCELLED)
        } else if (navigationRoutes.isNotEmpty()) {
            // Se vier rota nova, só envia reroute se já estiver em navegação
            if (isNavigationInProgress) {
                PluginUtilities.sendEvent(MapBoxEvents.REROUTE_ALONG)
            }
        }
    }
}


    // Map click/long click
    private val onMapLongClick = object : MapViewObserver(), OnMapLongClickListener {
        override fun onAttached(mapView: MapView) { mapView.gestures.addOnMapLongClickListener(this) }
        override fun onDetached(mapView: MapView) { mapView.gestures.removeOnMapLongClickListener(this) }
        override fun onMapLongClick(point: Point): Boolean {
            lastLocation?.let {
                val waypointSet = WaypointSet()
                waypointSet.add(Waypoint(Point.fromLngLat(it.longitude, it.latitude)))
                waypointSet.add(Waypoint(point))
                requestRoutes(waypointSet)
            }
            return false
        }
    }

    private val onMapClick = object : MapViewObserver(), OnMapClickListener {
        override fun onAttached(mapView: MapView) { mapView.gestures.addOnMapClickListener(this) }
        override fun onDetached(mapView: MapView) { mapView.gestures.removeOnMapClickListener(this) }
        override fun onMapClick(point: Point): Boolean {
            val waypoint = mapOf(
                "latitude" to point.latitude().toString(),
                "longitude" to point.longitude().toString()
            )
            PluginUtilities.sendEvent(MapBoxEvents.ON_MAP_TAP, JSONObject(waypoint).toString())
            return false
        }
    }
}
