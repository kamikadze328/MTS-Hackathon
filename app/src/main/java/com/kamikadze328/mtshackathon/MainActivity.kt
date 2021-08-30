package com.kamikadze328.mtshackathon

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.opencsv.CSVReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.*
import java.text.DecimalFormat
import kotlin.math.*


class MainActivity : AppCompatActivity(), OnMapReadyCallback {
    companion object {
        private val MTS_URL: (z: Int, x: Int, y: Int, type: NetworkType) -> String =
            { z, x, y, type -> "https://tiles.qsupport.mts.ru/${type.url_type}/$z/$x/$y/" }

        private val MEGAFON_URL: (z: Int, x: Int, y: Int, type: NetworkType) -> String =
            { z, x, y, type -> "https://coverage-map.megafon.ru/$z/$x/$y.png?layers=${type.url_type}" }

        private val BEELINE_URL: (z: Int, x: Int, y: Int, type: NetworkType) -> String =
            { z, x, y, type -> "https://static.beeline.ru/upload/tiles/${type.url_type}/current/$z/$x/$y.png" }

        private val COORDINATE_START = Pair(40, 22)

        private const val MIN_ZOOM = 6f
        private const val MAX_ZOOM = 12f

        private const val TOP_X = 49.110760
        private const val BOTTOM_X = 45.294232
        private const val LEFT_Y = 45.003002
        private const val RIGHT_Y = 50.597661

        val TOP_LEFT = LatLng(TOP_X, LEFT_Y)
        val TOP_RIGHT = LatLng(TOP_X, RIGHT_Y)
        val BOTTOM_LEFT = LatLng(BOTTOM_X, LEFT_Y)
        val BOTTOM_RIGHT = LatLng(BOTTOM_X, RIGHT_Y)


        val CENTER = LatLng((TOP_X + BOTTOM_X) / 2, (LEFT_Y + RIGHT_Y) / 2)

        val BOUNDS: LatLngBounds = LatLngBounds.builder()
            .include(TOP_LEFT)
            .include(TOP_RIGHT)
            .include(BOTTOM_LEFT)
            .include(BOTTOM_RIGHT)
            .build()
        private const val LOG_KEK = "kek"

        private fun calculateDistance(StartP: LatLng, EndP: LatLng): Double {
            val radius = 6371 // radius of earth in Km
            val lat1 = StartP.latitude
            val lat2 = EndP.latitude
            val lon1 = StartP.longitude
            val lon2 = EndP.longitude
            val halfDLat = Math.toRadians(lat2 - lat1) / 2
            val halfDLon = Math.toRadians(lon2 - lon1) / 2
            val a = (sin(halfDLat) * sin(halfDLat)
                    + (cos(Math.toRadians(lat1))
                    * cos(Math.toRadians(lat2)) * sin(
                halfDLon
                        * sin(halfDLon)
            )))
            val c = 2 * asin(sqrt(a))
            /*val valueResult = radius * c
            val km = valueResult / 1
            val newFormat = DecimalFormat("####")
            val kmInDec: Int = Integer.valueOf(newFormat.format(km))
            val meter = valueResult % 1000
            val meterInDec: Int = Integer.valueOf(newFormat.format(meter))*/
            //Log.d(LOG_KEK, "${radius * c * 1000f} m or ${radius * c}")
            return radius * c * 1000f
        }

        val HEIGHT = calculateDistance(TOP_LEFT, BOTTOM_LEFT).toFloat()
        val WIDTH = calculateDistance(BOTTOM_RIGHT, BOTTOM_LEFT).toFloat()
    }

    private val currentLayers = mutableSetOf<NetworkType>()
    private val currentOverlays = mutableListOf<GroundOverlay>()

    private var currentZoom = MIN_ZOOM

    private lateinit var mMap: GoogleMap
    private lateinit var currentLocationInfoMts: TextView
    private lateinit var currentLocationInfoMegafon: TextView
    private lateinit var currentLocationInfoBeeline: TextView

    private lateinit var currentLocationInfoMtsSquare: TextView
    private lateinit var currentLocationInfoMegafonSquare: TextView
    private lateinit var currentLocationInfoBeelineSquare: TextView

    private lateinit var currentLocationInfoCoordinates: TextView

    private lateinit var currentLocationInfo: ConstraintLayout

    private var currentMarker: Marker? = null
    private var currentSquare: Polyline? = null

    var mtsData: List<MyPoint>? = null
    var megafonData: List<MyPoint>? = null
    var beelineData: List<MyPoint>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        listOf(
            R.id.mts_2g, R.id.mts_3g, R.id.mts_4g,
            R.id.megafon_2g, R.id.megafon_3g, R.id.megafon_4g,
            R.id.beeline_2g, R.id.beeline_3g, R.id.beeline_4g,
        ).forEach { findViewById<CheckBox>(it).setOnClickListener(::onCheckBoxChanged) }

        currentLocationInfo = findViewById(R.id.current_location_info)
        currentLocationInfoMts = findViewById(R.id.current_location_info_mts)
        currentLocationInfoMegafon = findViewById(R.id.current_location_info_megafon)
        currentLocationInfoBeeline = findViewById(R.id.current_location_info_beeline)
        currentLocationInfoMtsSquare = findViewById(R.id.current_location_info_mts_square)
        currentLocationInfoMegafonSquare = findViewById(R.id.current_location_info_megafon_square)
        currentLocationInfoBeelineSquare = findViewById(R.id.current_location_info_beeline_square)
        currentLocationInfoCoordinates = findViewById(R.id.current_location_info_coordinates)

        findViewById<ImageView>(R.id.current_location_info_close_button).setOnClickListener {
            currentLocationInfo.visibility = View.GONE
            currentMarker?.remove()
            currentSquare?.remove()
        }
    }

    private fun onCheckBoxChanged(checkBox: View) {
        if (checkBox is CheckBox) {
            val type: NetworkType = when (checkBox.id) {
                R.id.mts_2g -> NetworkType.MTS_2G
                R.id.mts_3g -> NetworkType.MTS_3G
                R.id.mts_4g -> NetworkType.MTS_4G
                R.id.megafon_2g -> NetworkType.MEGAFON_2G
                R.id.megafon_3g -> NetworkType.MEGAFON_3G
                R.id.megafon_4g -> NetworkType.MEGAFON_4G
                R.id.beeline_2g -> NetworkType.BEELINE_2G
                R.id.beeline_3g -> NetworkType.BEELINE_3G
                R.id.beeline_4g -> NetworkType.BEELINE_4G
                else -> throw IllegalStateException()
            }

            if (checkBox.isChecked) {
                currentLayers.add(type)
            } else {
                currentLayers.remove(type)
            }
            updateLayers()
            //changeLayers(drawableId, checkBox.isChecked)
        }
    }

    private fun changeLayers(
        bitmap: Bitmap,
        center: LatLng,
        width: Float,
        height: Float
    ) {
        val settings = GroundOverlayOptions()
            .image(BitmapDescriptorFactory.fromBitmap(bitmap))
            .position(center, width, height)
        val overlay = mMap.addGroundOverlay(settings)
        currentOverlays.add(overlay)

    }

    private fun loadImage(
        url: String,
        center: LatLng,
        width: Float,
        height: Float
    ) {
        Glide.with(this)
            .asBitmap()
            .load(url)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    changeLayers(resource, center, width, height)
                }

                override fun onLoadCleared(placeholder: Drawable?) {}
            })
    }

    private fun updateLayers() {
        val z = currentZoom.toInt()
        val base = (z - MIN_ZOOM).toDouble() // 0, 1, 2, 3, 4, 5, 6

        val countSides = 2.0.pow(base).toInt()
        val baseX = COORDINATE_START.first * countSides
        val baseY = COORDINATE_START.second * countSides


        val sideWidthInCoor = (RIGHT_Y - LEFT_Y) / countSides
        val width = WIDTH / countSides

        val sideHeightInCoor = (TOP_X - BOTTOM_X) / countSides
        val height = HEIGHT / countSides

        for (overlay in currentOverlays) overlay.remove()
        currentOverlays.clear()


        for (x in 0 until countSides) {
            val myX = baseX + x
            for (y in 0 until countSides) {
                val myY = baseY + y
                currentLayers.forEach {
                    val url: String = when (it) {
                        NetworkType.MTS_2G -> MTS_URL(z, myX, myY, it)
                        NetworkType.MTS_3G -> MTS_URL(z, myX, myY, it)
                        NetworkType.MTS_4G -> MTS_URL(z, myX, myY, it)
                        NetworkType.MEGAFON_2G -> MEGAFON_URL(z, myX, myY, it)
                        NetworkType.MEGAFON_3G -> MEGAFON_URL(z, myX, myY, it)
                        NetworkType.MEGAFON_4G -> MEGAFON_URL(z, myX, myY, it)
                        NetworkType.BEELINE_2G -> BEELINE_URL(z, myX, myY, it)
                        NetworkType.BEELINE_3G -> BEELINE_URL(z, myX, myY, it)
                        NetworkType.BEELINE_4G -> BEELINE_URL(z, myX, myY, it)
                    }
                    val center = LatLng(
                        TOP_X - sideHeightInCoor * y - sideHeightInCoor / 2,
                        LEFT_Y + sideWidthInCoor * x + sideWidthInCoor / 2
                    )

                    loadImage(url, center, width, height)
                }
            }
        }
    }

    private fun searchTypes(
        latitude: Double,
        longitude: Double,
        topLeft: LatLng,
        bottomRight: LatLng
    ) {
        Log.d(LOG_KEK, "searchTypes")
        lifecycleScope.launch {
            setMtsText("Загружаю...")
            try {
                if (mtsData == null) {
                    openFile("mts.csv")?.let {
                        mtsData = it
                    } ?: setMtsText("")
                }
                launch {
                    mtsData?.let {
                        setMtsText(findNearestPoint(it, latitude, longitude))
                    }
                }
                launch {
                    mtsData?.let {
                        setMtsTextSquare(scanSquare(it, topLeft, bottomRight))
                    } ?: setMtsTextSquare("")
                }
            } catch (e: Exception) {
                setMtsText("")
            }

        }
        lifecycleScope.launch {
            setMegafonText("Загружаю...")
            try {
                if (megafonData == null) {
                    openFile("megafon.csv")?.let {
                        megafonData = it
                    } ?: setMegafonText("")
                }
                launch {
                    megafonData?.let {
                        Log.d(LOG_KEK, it.size.toString())
                        Log.d(LOG_KEK, findNearestPoint(it, latitude, longitude).toString())
                        setMegafonText(findNearestPoint(it, latitude, longitude))
                    }
                }
                launch {
                    megafonData?.let {
                        setMegafonTextSquare(scanSquare(it, topLeft, bottomRight))
                    } ?: setMegafonTextSquare("")
                }
            } catch (e: Exception) {
                setMegafonText("")
            }

        }
        lifecycleScope.launch {
            setBeelineText("Загружаю...")
            try {
                if (beelineData == null) {
                    openFile("beeline.csv")?.let {
                        beelineData = it
                    } ?: setBeelineText("")
                }
                launch {
                    beelineData?.let {
                        setBeelineText(findNearestPoint(it, latitude, longitude))
                    }
                }
                launch {
                    beelineData?.let {
                        setBeelineTextSquare(scanSquare(it, topLeft, bottomRight))
                    } ?: setBeelineTextSquare("")
                }
            } catch (e: Exception) {
                setBeelineText("")
            }
        }
    }

    private fun scanSquare(data: List<MyPoint>, topLeft: LatLng, bottomRight: LatLng): SquareData {

        val inside = data.filter {
            it.y > topLeft.latitude && it.y < bottomRight.latitude
                    && it.x < topLeft.longitude && it.x > bottomRight.longitude
        }
        Log.d(LOG_KEK, "scanSquare - ${inside.size}")

        val allCount = inside.size.toDouble()
        val is2gCount = inside.count { it.is2g }
        val is3gCount = inside.count { it.is3g }
        val is4gCount = inside.count { it.is4g }
        Log.d(LOG_KEK, "2g - $is2gCount, 3g - $is3gCount, 4g - $is4gCount")
        return SquareData(is2gCount / allCount * 100, is3gCount / allCount * 100, is4gCount / allCount * 100)
    }


    private fun findNearestPoint(data: List<MyPoint>, latitude: Double, longitude: Double) =
        data.first {
            it.y < latitude && it.x > longitude
        }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun openFile(filename: String): List<MyPoint>? = withContext(Dispatchers.IO) {
        for (file in assets.list("")!!) {
            if (file == filename) {
                val reader = CSVReader(InputStreamReader(assets.open(filename)))
                val data = reader.readAll()
                data.removeAt(0) //header
                return@withContext data.map { it.toPoint() }
            }
        }
        return@withContext null
    }


    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.setMinZoomPreference(MIN_ZOOM)
        mMap.setMaxZoomPreference(MAX_ZOOM)

        mMap.setLatLngBoundsForCameraTarget(BOUNDS)

        mMap.setOnMapClickListener {
            val f = DecimalFormat("###.000000")
            val coordinatesText = "${f.format(it.latitude)}, ${f.format(it.longitude)}"
            Log.d(LOG_KEK, coordinatesText)
            currentLocationInfo.visibility = View.VISIBLE
            currentLocationInfoCoordinates.text = coordinatesText

            currentMarker?.remove()
            currentMarker = mMap.addMarker(MarkerOptions().position(it))

            currentSquare?.remove()
            val leftBottom = LatLng(it.latitude - .5, it.longitude - .5)
            val rightBottom = LatLng(it.latitude + .5, it.longitude - .5)
            val leftTop = LatLng(it.latitude - .5, it.longitude + .5)
            val rightTop = LatLng(it.latitude + .5, it.longitude + .5)
            val polylineOptions = PolylineOptions()
                .add(leftBottom)
                .add(leftTop) // Same latitude, and 30km to the west
                .add(rightTop) // North of the previous point, but at the same longitude
                .add(rightBottom) // Same longitude, and 16km to the south
                .add(leftBottom)
            searchTypes(it.latitude, it.longitude, leftTop, rightBottom)

            currentSquare = mMap.addPolyline(polylineOptions)
        }

        mMap.setOnCameraMoveListener {
            val oldValue = currentZoom
            currentZoom = floor(mMap.cameraPosition.zoom.toDouble()).toFloat()
            if (oldValue != currentZoom)
                updateLayers()
        }

        mMap.addMarker(
            MarkerOptions()
                .position(TOP_LEFT)
                .title("TOP_LEFT")
        )
        mMap.addMarker(
            MarkerOptions()
                .position(TOP_RIGHT)
                .title("TOP_RIGHT")
        )
        mMap.addMarker(
            MarkerOptions()
                .position(BOTTOM_LEFT)
                .title("BOTTOM_LEFT")
        )
        mMap.addMarker(
            MarkerOptions()
                .position(BOTTOM_RIGHT)
                .title("BOTTOM_RIGHT")
        )
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(CENTER, MIN_ZOOM))
    }

    private fun createStrInfo(point: MyPoint): String {
        val str = StringBuilder()
        if (point.is2g)
            str.append("2g")

        if (point.is3g) {
            if (str.isNotEmpty()) str.append(", ")
            str.append("3g")
        }
        if (point.is4g) {
            if (str.isNotEmpty()) str.append(", ")
            str.append("4g")
        }
        return str.toString()
    }

    private fun createStrInfo(data: SquareData): String {
        val str = StringBuilder()
        val f = DecimalFormat("##.00")
        str.append("2g - ${f.format(data.perIs2g)}, ")
        str.append("3g - ${f.format(data.perIs3g)}, ")
        str.append("4g - ${f.format(data.perIs4g)}")
        return str.toString()
    }

    private fun setMtsText(point: MyPoint) {
        setMtsText(createStrInfo(point))
    }

    private fun setMegafonText(point: MyPoint) {
        setMegafonText(createStrInfo(point))
    }

    private fun setBeelineText(point: MyPoint) {
        setBeelineText(createStrInfo(point))
    }

    private fun setMtsTextSquare(data: SquareData) {
        setMtsTextSquare(createStrInfo(data))
    }

    private fun setMegafonTextSquare(data: SquareData) {
        setMegafonTextSquare(createStrInfo(data))
    }

    private fun setBeelineTextSquare(data: SquareData) {
        setBeelineTextSquare(createStrInfo(data))
    }

    private fun setMtsText(text: String) {
        val str = "Mts: ${if (text.isEmpty()) "((" else text}"
        currentLocationInfoMts.text = str
    }

    private fun setMegafonText(text: String) {
        val str = "Megafon: ${if (text.isEmpty()) "((" else text}"
        currentLocationInfoMegafon.text = str
    }

    private fun setBeelineText(text: String) {
        val str = "Beeline: ${if (text.isEmpty()) "((" else text}"
        currentLocationInfoBeeline.text = str
    }

    private fun setMtsTextSquare(text: String) {
        val str = "Mts: ${if (text.isEmpty()) "((" else text}"
        currentLocationInfoMtsSquare.text = str
    }

    private fun setMegafonTextSquare(text: String) {
        val str = "Megafon: ${if (text.isEmpty()) "((" else text}"
        currentLocationInfoMegafonSquare.text = str
    }

    private fun setBeelineTextSquare(text: String) {
        val str = "Beeline: ${if (text.isEmpty()) "((" else text}"
        currentLocationInfoBeelineSquare.text = str
    }


    enum class NetworkType(val url_type: String) {
        MTS_2G("g2_New"), MTS_3G("g3_New"), MTS_4G("lte_New"),
        MEGAFON_2G("2g"), MEGAFON_3G("3g"), MEGAFON_4G("lte"),
        BEELINE_2G("2G"), BEELINE_3G("3G"), BEELINE_4G("4G"),
    }
}

fun Array<String>.toPoint() = MyPoint(
    x = get(0).toDouble(),
    y = get(1).toDouble(),
    is2g = get(2).toBoolean(),
    is3g = get(3).toBoolean(), is4g = get(4).toBoolean(),
)

data class MyPoint(
    val x: Double,
    val y: Double,
    val is2g: Boolean,
    val is3g: Boolean,
    val is4g: Boolean
)

data class SquareData(
    val perIs2g: Double,
    val perIs3g: Double,
    val perIs4g: Double,
)