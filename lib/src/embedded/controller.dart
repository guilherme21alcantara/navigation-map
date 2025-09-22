import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_mapbox_navigation/src/models/models.dart';

/// Controller for a single MapBox Navigation instance
/// running on the host platform.
class MapBoxNavigationViewController {
  /// Constructor
  MapBoxNavigationViewController(
    int id,
    ValueSetter<RouteEvent>? eventNotifier,
  ) {
    _methodChannel = MethodChannel('flutter_mapbox_navigation/$id');
    _methodChannel.setMethodCallHandler(_handleMethod);

    _eventChannel = EventChannel('flutter_mapbox_navigation/$id/events');
    _routeEventNotifier = eventNotifier;
  }

  late final MethodChannel _methodChannel;
  late final EventChannel _eventChannel;
  ValueSetter<RouteEvent>? _routeEventNotifier;
  StreamSubscription<RouteEvent>? _routeEventSubscription;

  /// Current Device OS Version
  Future<String> get platformVersion async {
    final result = await _methodChannel.invokeMethod('getPlatformVersion');
    return result as String;
  }

  /// Total distance remaining in meters along route.
  Future<double> get distanceRemaining async {
    final result = await _methodChannel.invokeMethod('getDistanceRemaining');
    return result as double;
  }

  /// Total seconds remaining on all legs.
  Future<double> get durationRemaining async {
    final result = await _methodChannel.invokeMethod('getDurationRemaining');
    return result as double;
  }

  /// Builds a route with given waypoints and options
  Future<bool> buildRoute({
    required List<WayPoint> wayPoints,
    MapBoxOptions? options,
  }) async {
    assert(wayPoints.length > 1, 'WayPoints must be at least 2');

    if (Platform.isIOS && wayPoints.length > 3 && options?.mode != null) {
      assert(
        options!.mode != MapBoxNavigationMode.drivingWithTraffic,
        'Cannot use drivingWithTraffic mode with more than 3 stops on iOS',
      );
    }

    final pointList = <Map<String, dynamic>>[];
    for (var i = 0; i < wayPoints.length; i++) {
      final wp = wayPoints[i];
      assert(wp.name != null, 'WayPoint name is required');
      assert(wp.latitude != null, 'WayPoint latitude is required');
      assert(wp.longitude != null, 'WayPoint longitude is required');

      pointList.add({
        'Order': i,
        'Name': wp.name,
        'Latitude': wp.latitude,
        'Longitude': wp.longitude,
        'IsSilent': wp.isSilent,
      });
    }

    final wayPointMap = {
      for (var i = 0; i < pointList.length; i++) i: pointList[i],
    };

    final args = options?.toMap() ?? {};
    args['wayPoints'] = wayPointMap;

    final stream = _streamRouteEvent;
    if (stream != null) {
      _routeEventSubscription = stream.listen(_onProgressData);
    } else {
      debugPrint('⚠️ Stream de eventos não disponível ao construir rota.');
    }

    final result = await _methodChannel.invokeMethod('buildRoute', args);
    return result as bool;
  }

  /// Starts listening for route events
  Future<void> initialize() async {
    final stream = _streamRouteEvent;
    if (stream != null) {
      _routeEventSubscription = stream.listen(_onProgressData);
    } else {
      debugPrint('⚠️ Stream de eventos não disponível na inicialização.');
    }
  }

  /// Clears the current route
  Future<bool?> clearRoute() async {
    return await _methodChannel.invokeMethod('clearRoute');
  }

  /// Starts Free Drive Mode
  Future<bool?> startFreeDrive({MapBoxOptions? options}) async {
    final args = options?.toMap();
    return await _methodChannel.invokeMethod('startFreeDrive', args);
  }

  /// Starts turn-by-turn navigation
  Future<bool?> startNavigation({MapBoxOptions? options}) async {
    final args = options?.toMap();
    return await _methodChannel.invokeMethod('startNavigation', args);
  }

  /// Ends navigation and closes the view
  Future<bool?> finishNavigation() async {
    final result = await _methodChannel.invokeMethod('finishNavigation');
    return result as bool?;
  }

  /// Handles messages from native platform
  Future<dynamic> _handleMethod(MethodCall call) async {
    switch (call.method) {
      case 'sendFromNative':
        final text = call.arguments as String?;
        return Future.value('Text from native: $text');
      default:
        return null;
    }
  }

  /// Disposes the controller and cancels subscriptions
  void dispose() {
    _routeEventSubscription?.cancel();
  }

  /// Handles incoming route events
  void _onProgressData(RouteEvent event) {
    _routeEventNotifier?.call(event);
  }

  /// Stream of route events from native
  Stream<RouteEvent>? get _streamRouteEvent {
    try {
      return _eventChannel
          .receiveBroadcastStream()
          .map((dynamic event) => _parseRouteEvent(event as String))
          .where((event) => event != null)
          .cast<RouteEvent>();
    } catch (e) {
      debugPrint('⚠️ Erro ao iniciar stream de eventos: $e');
      return null;
    }
  }

  /// Parses JSON route events safely
  RouteEvent? _parseRouteEvent(String jsonString) {
    try {
      final map = json.decode(jsonString) as Map<String, dynamic>;
      final progressEvent = RouteProgressEvent.fromJson(map);

      if (progressEvent.isProgressEvent ?? false) {
        return RouteEvent(
          eventType: MapBoxEvent.progress_change,
          data: progressEvent,
        );
      } else {
        return RouteEvent.fromJson(map);
      }
    } catch (e) {
      if (kDebugMode) {
        debugPrint('⚠️ Evento ignorado: JSON malformado\n→ $jsonString');
      }
      return null;
    }
  }
}
