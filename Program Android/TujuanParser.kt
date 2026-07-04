package com.skripsi.smart_cane

import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import java.io.IOException

object TujuanParser {

    private val client = OkHttpClient()
    private val gson   = Gson()

    data class HasilParse(
        val namaLokasi : String,
        val lat        : Double,
        val lon        : Double,
        val ditemukan  : Boolean
    )

    fun ekstrakTujuan(perintah: String): String {
        return perintah.lowercase().trim()
            .removePrefix("ke ")
            .removePrefix("menuju ")
            .removePrefix("navigasi ke ")
            .removePrefix("pergi ke ")
            .removePrefix("antar ke ")
            .removePrefix("cari ")
            .trim()
    }

    fun cariLokasi(
        namaLokasi : String,
        kotaBias   : String = "Malang",
        onHasil    : (HasilParse) -> Unit
    ) {
        val query = "$namaLokasi, $kotaBias"
        val url   = "https://nominatim.openstreetmap.org/search" +
                "?q=${query.replace(" ", "+")}" +
                "&format=json&limit=1&countrycodes=id"

        Log.d("Geocoding", "Mencari: $query")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "SmartCaneApp/1.0")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("Geocoding", "Gagal: ${e.message}")
                onHasil(HasilParse(namaLokasi, 0.0, 0.0, false))
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                try {
                    val list = gson.fromJson(body, List::class.java)
                    if (list.isNullOrEmpty()) {
                        onHasil(HasilParse(namaLokasi, 0.0, 0.0, false))
                        return
                    }
                    val item = list[0] as Map<*, *>
                    val lat  = (item["lat"] as String).toDouble()
                    val lon  = (item["lon"] as String).toDouble()
                    onHasil(HasilParse(namaLokasi, lat, lon, true))
                } catch (e: Exception) {
                    onHasil(HasilParse(namaLokasi, 0.0, 0.0, false))
                }
            }
        })
    }
}