// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.neo.ugurfilm

import com.fasterxml.jackson.annotation.JsonProperty


data class AjaxSource(
    @JsonProperty("status")      val status: String,
    @JsonProperty("iframe")      val iframe: String,
    @JsonProperty("alternative") val alternative: String,
)