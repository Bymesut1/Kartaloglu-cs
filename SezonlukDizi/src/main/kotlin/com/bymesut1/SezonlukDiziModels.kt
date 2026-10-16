package com.bymesut1

import com.fasterxml.jackson.annotation.JsonProperty

data class Kaynak(
    @JsonProperty("status") val status: String?,
    @JsonProperty("data")   val data: List<KaynakVeri>?
)

data class KaynakVeri(
    @JsonProperty("id")     val id: String?,
    @JsonProperty("baslik") val baslik: String?
)

data class AspData(
    val alternatif: String,
    val embed: String
)
