package com.onguard.data.remote.dto

import com.google.gson.annotations.SerializedName

data class ScamCheckRequest(
    @SerializedName("keyword_type")
    val keywordType: String,  // "account" or "phone"

    @SerializedName("keyword")
    val keyword: String,

    @SerializedName("add_info")
    val addInfo: String? = null
)

data class ScamCheckResponse(
    @SerializedName("result")
    val result: Boolean,

    @SerializedName("count")
    val count: Int,

    @SerializedName("data")
    val data: List<ScamData>?
)

data class ScamData(
    @SerializedName("id")
    val id: String,

    @SerializedName("type")
    val type: String,

    @SerializedName("description")
    val description: String?
)
