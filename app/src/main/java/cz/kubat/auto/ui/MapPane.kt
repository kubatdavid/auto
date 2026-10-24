package cz.kubat.auto.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Mapy.com raster tiles (retina) rendered by MapLibre. */
private fun styleJson(mapyKey: String): String {
    val source = JSONObject()
        .put("type", "raster")
        .put("tiles", JSONArray().put("https://api.mapy.com/v1/maptiles/basic/256@2x/{z}/{x}/{y}?lang=cs&apikey=$mapyKey"))
        .put("tileSize", 256)
        .put("maxzoom", 19)
        .put("attribution", "© Seznam.cz a.s. a další · mapy.com")
    return JSONObject()
        .put("version", 8)
        .put("sources", JSONObject().put("mapy", source))
        .put("layers", JSONArray().put(JSONObject().put("id", "mapy").put("type", "raster").put("source", "mapy")))
        .toString()
}

@Composable
fun MapPane(
    mapyKey: String,
    lat: Double?,
    lon: Double?,
    track: List<Pair<Double, Double>>,
    destination: Pair<Double, Double>?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var follow by remember { mutableStateOf(true) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    Box(modifier) {
        AndroidView(factory = {
            mapView.also { mv ->
                mv.getMapAsync { m ->
                    m.cameraPosition = CameraPosition.Builder().target(LatLng(49.8, 15.5)).zoom(7.0).build()
                    m.uiSettings.isRotateGesturesEnabled = false
                    m.addOnCameraMoveStartedListener { reason ->
                        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) follow = false
                    }
                    map = m
                }
            }
        }, modifier = Modifier.fillMaxSize())

        if (mapyKey.isEmpty()) {
            Text(
                "Map needs the server token (Settings)",
                color = Color(0xFF8A93A3), fontSize = 14.sp, modifier = Modifier.align(Alignment.Center),
            )
        }
        SmallFloatingActionButton(
            onClick = { follow = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
        ) { Icon(Icons.Filled.LocationOn, contentDescription = "Follow position") }
    }

    LaunchedEffect(map, mapyKey) {
        val m = map ?: return@LaunchedEffect
        if (mapyKey.isEmpty()) return@LaunchedEffect
        m.setStyle(Style.Builder().fromJson(styleJson(mapyKey))) { s ->
            s.addSource(GeoJsonSource("track"))
            s.addSource(GeoJsonSource("me"))
            s.addSource(GeoJsonSource("dest"))
            s.addLayer(
                LineLayer("track-line", "track").withProperties(
                    PropertyFactory.lineColor("#2563EB"), PropertyFactory.lineWidth(5f), PropertyFactory.lineOpacity(0.8f),
                )
            )
            s.addLayer(
                CircleLayer("dest-dot", "dest").withProperties(
                    PropertyFactory.circleColor("#E11D48"), PropertyFactory.circleRadius(9f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(3f),
                )
            )
            s.addLayer(
                CircleLayer("me-dot", "me").withProperties(
                    PropertyFactory.circleColor("#16A34A"), PropertyFactory.circleRadius(9f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(3f),
                )
            )
            style = s
            cz.kubat.auto.data.AppLog.i("Map: Mapy.com style loaded")
        }
    }

    LaunchedEffect(style, lat, lon) {
        val s = style ?: return@LaunchedEffect
        if (lat == null || lon == null) return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>("me")?.setGeoJson(Point.fromLngLat(lon, lat))
        if (follow) {
            val m = map ?: return@LaunchedEffect
            val zoom = if (m.cameraPosition.zoom < 12) 15.0 else m.cameraPosition.zoom
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom), 800)
        }
    }

    LaunchedEffect(style, track.size) {
        val s = style ?: return@LaunchedEffect
        if (track.size < 2) return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>("track")
            ?.setGeoJson(LineString.fromLngLats(track.map { Point.fromLngLat(it.second, it.first) }))
    }

    LaunchedEffect(style, destination) {
        val s = style ?: return@LaunchedEffect
        val d = destination ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>("dest")?.setGeoJson(Point.fromLngLat(d.second, d.first))
        follow = false
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(d.first, d.second), 14.0), 800)
    }
}
